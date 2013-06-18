package com.splicemachine.si.impl;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.splicemachine.si.data.api.SDataLib;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.apache.hadoop.hbase.filter.Filter.ReturnCode.INCLUDE;
import static org.apache.hadoop.hbase.filter.Filter.ReturnCode.NEXT_COL;
import static org.apache.hadoop.hbase.filter.Filter.ReturnCode.SKIP;

/**
 * Contains the logic for performing an HBase-style filter using "snapshot isolation" logic. This means it filters out
 * data that should not be seen by the transaction that is performing the read operation (either a "get" or a "scan").
 */
public class FilterState<Data, Result, KeyValue, Put, Delete, Get, Scan, OperationWithAttributes, Lock> {
    static final Logger LOG = Logger.getLogger(FilterState.class);

    /**
     * The transactions that have been loaded as part of running this filter.
     */
    final Cache<Long, Transaction> transactionCache;
    final Cache<Long, Object[]> visibleCache;

    private final ImmutableTransaction myTransaction;
    private final SDataLib<Data, Result, KeyValue, OperationWithAttributes, Put, Delete, Get, Scan, Lock> dataLib;
    private final DataStore dataStore;
    private final TransactionStore transactionStore;
    private final RollForwardQueue rollForwardQueue;
    private final boolean includeSIColumn;
    private final boolean includeUncommittedAsOfStart;

    private final FilterRowState<Data, Result, KeyValue, Put, Delete, Get, Scan, OperationWithAttributes, Lock> rowState;
    private final DecodedKeyValue<Data, Result, KeyValue, Put, Delete, Get, Scan, OperationWithAttributes, Lock> keyValue;

    private final TransactionSource transactionSource;

    FilterState(SDataLib dataLib, DataStore dataStore, TransactionStore transactionStore,
                RollForwardQueue rollForwardQueue, boolean includeSIColumn, boolean includeUncommittedAsOfStart,
                ImmutableTransaction myTransaction) {
        this.transactionSource = new TransactionSource() {
            @Override
            public Transaction getTransaction(long timestamp) throws IOException {
                return getTransactionFromFilterCache(timestamp);
            }
        };
        this.dataLib = dataLib;
        this.keyValue = new DecodedKeyValue(dataLib);
        this.dataStore = dataStore;
        this.transactionStore = transactionStore;
        this.rollForwardQueue = rollForwardQueue;
        this.includeSIColumn = includeSIColumn;
        this.includeUncommittedAsOfStart = includeUncommittedAsOfStart;
        this.myTransaction = myTransaction;

        transactionCache = CacheBuilder.newBuilder().maximumSize(10000).expireAfterWrite(10, TimeUnit.MINUTES).build();
        visibleCache = CacheBuilder.newBuilder().maximumSize(10000).expireAfterWrite(2, TimeUnit.MINUTES).build();

        // initialize internal state
        this.rowState = new FilterRowState(dataLib);
    }

    /**
     * The public entry point. This returns an HBase filter code and is expected to serve as the heart of an HBase filter
     * implementation.
     * The order of the column families is important. It is expected that the SI family will be processed first.
     */
    Filter.ReturnCode filterKeyValue(KeyValue dataKeyValue) throws IOException {
        keyValue.setKeyValue(dataKeyValue);
        rowState.updateCurrentRow(keyValue);
        return filterByColumnType();
    }

    /**
     * Look at the column family and qualifier to determine how to "dispatch" the current keyValue.
     */
    private Filter.ReturnCode filterByColumnType() throws IOException {
        if (rowState.inData) {
            return processUserDataShortCircuit();
        }
        final KeyValueType type = dataStore.getKeyValueType(keyValue.family(), keyValue.qualifier());
        if (type.equals(KeyValueType.TOMBSTONE)) {
            return processTombstone();
        } else if (type.equals(KeyValueType.COMMIT_TIMESTAMP)) {
            if (includeSIColumn && !rowState.isSiColumnIncluded()) {
                processCommitTimestamp();
                return processCommitTimestampAsUserData();
            } else {
                return processCommitTimestamp();
            }
        } else if (type.equals(KeyValueType.USER_DATA)) {
            return processUserDataSetupShortCircuit();
        } else {
            return processUnknownFamilyData();
        }
    }

    private Filter.ReturnCode processUserDataSetupShortCircuit() throws IOException {
        final Filter.ReturnCode result = processUserData();
        rowState.inData = true;
        if (rowState.transactionCache.size() == 1) {
            rowState.shortCircuit = result;
        }
        return result;
    }

    private Filter.ReturnCode processUserDataShortCircuit() throws IOException {
        if (rowState.shortCircuit != null) {
            return rowState.shortCircuit;
        } else {
            return processUserData();
        }
    }

    private Filter.ReturnCode processCommitTimestampAsUserData() throws IOException {
        boolean later = false;
        for (Long tombstoneTimestamp : rowState.tombstoneTimestamps) {
            if (tombstoneTimestamp < keyValue.timestamp()) {
                later = true;
                break;
            }
        }
        if (later) {
            rowState.rememberCommitTimestamp(keyValue.keyValue());
            return SKIP;
        } else {
            final KeyValue oldKeyValue = keyValue.keyValue();
            boolean include = false;
            for (KeyValue kv : rowState.getCommitTimestamps()) {
                keyValue.setKeyValue(kv);
                if (processUserData().equals(INCLUDE)) {
                    include = true;
                    break;
                }
            }
            keyValue.setKeyValue(oldKeyValue);
            if (include) {
                rowState.setSiColumnIncluded();
                return INCLUDE;
            } else {
                final Filter.ReturnCode returnCode = processUserData();
                if (returnCode.equals(SKIP)) {
                } else {
                    rowState.setSiColumnIncluded();
                }
                return returnCode;
            }
        }
    }

    /**
     * Handles the "commit timestamp" values that SI writes to each data row. Processing these values causes the
     * relevant transaction data to be loaded up into the local cache.
     */
    private Filter.ReturnCode processCommitTimestamp() throws IOException {
        Transaction transaction = transactionCache.getIfPresent(keyValue.timestamp());
        if (transaction == null) {
            transaction = processCommitTimestampDirect();
        }
        rowState.transactionCache.put(transaction.getTransactionId().getId(), transaction);
        return SKIP;
    }

    private Transaction processCommitTimestampDirect() throws IOException {
        Transaction transaction;
        if (dataStore.isSINull(keyValue.value())) {
            transaction = handleUnknownTransactionStatus();
        } else if (dataStore.isSIFail(keyValue.value())) {
            transaction = transactionStore.makeFailedTransaction(keyValue.timestamp());
            transactionCache.put(keyValue.timestamp(), transaction);
        } else {
            transaction = transactionStore.makeCommittedTransaction(keyValue.timestamp(), (Long) dataLib.decode(keyValue.value(), Long.class));
            transactionCache.put(keyValue.timestamp(), transaction);
        }
        return transaction;
    }

    private Transaction getTransactionFromFilterCache(long timestamp) throws IOException {
        Transaction transaction = transactionCache.getIfPresent(timestamp);
        if (transaction == null) {
            if (!myTransaction.getEffectiveReadCommitted() && !myTransaction.getEffectiveReadUncommitted()
                    && !myTransaction.isDescendant(transactionStore.getImmutableTransaction(timestamp))) {
                transaction = transactionStore.getTransactionAsOf(timestamp, myTransaction.getLongTransactionId());
            } else {
                transaction = transactionStore.getTransaction(timestamp);
            }
            transactionCache.put(transaction.getTransactionId().getId(), transaction);
        }
        return transaction;
    }

    /**
     * Handles the case where the commit timestamp cell contains a begin timestamp, but doesn't have an
     * associated commit timestamp in the cell. This means the transaction table needs to be consulted to find out
     * the current status of the transaction.
     */
    private Transaction handleUnknownTransactionStatus() throws IOException {
        final Transaction cachedTransaction = rowState.transactionCache.get(keyValue.timestamp());
        if (cachedTransaction == null) {
            final Transaction dataTransaction = getTransactionFromFilterCache(keyValue.timestamp());
            final Object[] visibleResult = checkVisibility(dataTransaction);
            final TransactionStatus dataStatus = (TransactionStatus) visibleResult[1];
            if (dataStatus.equals(TransactionStatus.COMMITTED) || dataStatus.equals(TransactionStatus.ROLLED_BACK)
                    || dataStatus.equals(TransactionStatus.ERROR)) {
                rollForward(dataTransaction);
            }
            return dataTransaction;
        } else {
            return cachedTransaction;
        }
    }

    private Object[] checkVisibility(Transaction dataTransaction) throws IOException {
        final long timestamp = dataTransaction.getLongTransactionId();
        Object[] result = visibleCache.getIfPresent(timestamp);
        if (result == null) {
            result = myTransaction.isVisible(dataTransaction, transactionSource);
            visibleCache.put(timestamp, result);
        }
        return result;
    }

    /**
     * Update the data row to remember the commit timestamp of the transaction. This avoids the need to look the
     * transaction up in the transaction table again the next time this row is read.
     */
    private void rollForward(Transaction transaction) throws IOException {
        final TransactionStatus effectiveStatus = transaction.getEffectiveStatus();
        if (rollForwardQueue != null &&
                (effectiveStatus.equals(TransactionStatus.COMMITTED)
                        || effectiveStatus.equals(TransactionStatus.ERROR)
                        || effectiveStatus.equals(TransactionStatus.ROLLED_BACK))) {
            // TODO: revisit this in light of nested independent transactions
            dataStore.recordRollForward(rollForwardQueue, transaction, keyValue.row());
        }
    }

    /**
     * Under SI, deleting a row is handled as adding a row level tombstone record in the SI family. This function reads
     * those values in for a given row and stores the tombstone timestamp so it can be used later to filter user data.
     */
    private Filter.ReturnCode processTombstone() throws IOException {
        rowState.setTombstoneTimestamp(keyValue.timestamp());
        if (includeUncommittedAsOfStart && !rowState.isSiTombstoneIncluded() && keyValue.timestamp() < myTransaction.getTransactionId().getId()) {
            rowState.setSiTombstoneIncluded();
            return INCLUDE;
        } else {
            return SKIP;
        }
    }

    /**
     * Handle a cell that represents user data. Filter it based on the various timestamps and transaction status.
     */
    private Filter.ReturnCode processUserData() throws IOException {
        if (doneWithColumn()) {
            return NEXT_COL;
        } else {
            return filterUserDataByTimestamp();
        }
    }

    /**
     * Consider a cell of user data and decide whether to use it as "the" value for the column (in the context of the
     * current transaction) based on the various timestamp values and transaction status.
     */
    private Filter.ReturnCode filterUserDataByTimestamp() throws IOException {
        if (tombstoneAfterData()) {
            return NEXT_COL;
        } else if (isVisibleToCurrentTransaction()) {
            proceedToNextColumn();
            return INCLUDE;
        } else {
            if (includeUncommittedAsOfStart) {
                if (isUncommittedAsOfStart()) {
                    return INCLUDE;
                } else {
                    return SKIP;
                }
            } else {
                return SKIP;
            }
        }
    }

    private boolean isUncommittedAsOfStart() throws IOException {
        final Transaction transaction = loadTransaction();
        return myTransaction.isUncommittedAsOfStart(transaction, transactionSource);
    }

    /**
     * The version of HBase we are using does not support the newer "INCLUDE & NEXT_COL" return code
     * so this manually does the equivalent.
     */
    private void proceedToNextColumn() {
        rowState.lastValidQualifier = keyValue.qualifier();
    }

    /**
     * The second half of manually implementing our own "INCLUDE & NEXT_COL" return code.
     */
    private boolean doneWithColumn() {
        return dataLib.valuesEqual(keyValue.qualifier(), rowState.lastValidQualifier);
    }

    /**
     * Is there a row level tombstone that supercedes the current cell?
     */
    private boolean tombstoneAfterData() throws IOException {
        for (long tombstone : rowState.tombstoneTimestamps) {
            final Transaction tombstoneTransaction = rowState.transactionCache.get(tombstone);
            final Object[] visibleResult = checkVisibility(tombstoneTransaction);
            final boolean visible = (Boolean) visibleResult[0];
            if (visible && (keyValue.timestamp() < tombstone
                    || (keyValue.timestamp() == tombstone && dataStore.isSINull(keyValue.value())))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Should the current cell be visible to the current transaction? This is the core of the filtering of the data
     * under SI. It handles the various cases of when data should be visible.
     */
    private boolean isVisibleToCurrentTransaction() throws IOException {
        if (keyValue.timestamp() == myTransaction.getTransactionId().getId() && !myTransaction.getTransactionId().independentReadOnly) {
            return true;
        } else {
            final Transaction transaction = loadTransaction();
            return (Boolean) checkVisibility(transaction)[0];
        }
    }

    /**
     * Retrieve the transaction for the current cell from the local cache.
     */
    private Transaction loadTransaction() throws IOException {
        final Transaction transaction = rowState.transactionCache.get(keyValue.timestamp());
        if (transaction == null) {
            throw new RuntimeException("All transactions should already be loaded from the si family for the data row, transaction Id: " + keyValue.timestamp());
        }
        return transaction;
    }

    /**
     * This is not expected to happen, but if there are extra unknown column families in the results they will be skipped.
     */
    private Filter.ReturnCode processUnknownFamilyData() {
        return SKIP;
    }

}
