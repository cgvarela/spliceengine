package com.splicemachine.si.impl;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import com.splicemachine.si.impl.store.IgnoreTxnCacheSupplier;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.util.Bytes;

import com.google.protobuf.ByteString;
import com.splicemachine.constants.SIConstants;
import com.splicemachine.constants.SpliceConstants;
import com.splicemachine.encoding.MultiFieldEncoder;
import com.splicemachine.hbase.table.BetterHTablePool;
import com.splicemachine.hbase.table.SpliceHTableFactory;
import com.splicemachine.si.api.RowAccumulator;
import com.splicemachine.si.api.SIFactory;
import com.splicemachine.si.api.TransactionalRegion;
import com.splicemachine.si.api.TxnStore;
import com.splicemachine.si.api.TxnSupplier;
import com.splicemachine.si.api.Txn.IsolationLevel;
import com.splicemachine.si.api.Txn.State;
import com.splicemachine.si.coprocessor.TxnMessage;
import com.splicemachine.si.coprocessor.TxnMessage.Txn;
import com.splicemachine.si.data.api.SDataLib;
import com.splicemachine.si.data.api.STableReader;
import com.splicemachine.si.data.api.STableWriter;
import com.splicemachine.si.data.hbase.HDataLib;
import com.splicemachine.si.data.hbase.HPoolTableSource;
import com.splicemachine.si.data.hbase.HRowAccumulator;
import com.splicemachine.si.data.hbase.HTableReader;
import com.splicemachine.si.data.hbase.HTableWriter;
import com.splicemachine.si.impl.region.HTransactionLib;
import com.splicemachine.si.impl.region.RegionTxnStore;
import com.splicemachine.si.impl.region.STransactionLib;
import com.splicemachine.storage.EntryDecoder;
import com.splicemachine.storage.EntryPredicateFilter;

public class SIFactoryImpl implements SIFactory<TxnMessage.Txn> {
	
	public static final SDataLib dataLib = new HDataLib();
	public static final STableWriter tableWriter = new HTableWriter();
	public static final STransactionLib transactionLib = new HTransactionLib();

	

	@Override
	public RowAccumulator getRowAccumulator(EntryPredicateFilter predicateFilter, EntryDecoder decoder,
			boolean countStar) {
		return new HRowAccumulator(dataLib,predicateFilter,decoder,countStar);
	}

	@Override
	public STableWriter getTableWriter() {
		return tableWriter;
	}

	@Override
	public SDataLib getDataLib() {
		return dataLib;
	}


	@Override
	public DataStore getDataStore() {
		return new DataStore(getDataLib(), getTableReader(),getTableWriter(),
				SIConstants.SI_NEEDED,
				SIConstants.SI_DELETE_PUT,
				SIConstants.SNAPSHOT_ISOLATION_COMMIT_TIMESTAMP_COLUMN_BYTES,
				SIConstants.SNAPSHOT_ISOLATION_TOMBSTONE_COLUMN_BYTES,
				HConstants.EMPTY_BYTE_ARRAY,
				SIConstants.SNAPSHOT_ISOLATION_ANTI_TOMBSTONE_VALUE_BYTES,
				SIConstants.SNAPSHOT_ISOLATION_FAILED_TIMESTAMP,
				SIConstants.DEFAULT_FAMILY_BYTES,
				DefaultCellTypeParser.INSTANCE
			);
	}

	@Override
	public STableReader getTableReader() {
		BetterHTablePool hTablePool = new BetterHTablePool(new SpliceHTableFactory(),
				SpliceConstants.tablePoolCleanerInterval, TimeUnit.SECONDS,
				SpliceConstants.tablePoolMaxSize,SpliceConstants.tablePoolCoreSize);
		final HPoolTableSource tableSource = new HPoolTableSource(hTablePool);
		final STableReader reader;
		try {
			return new HTableReader(tableSource);
		} catch (IOException e) {
			throw new RuntimeException(e);
	}

	}

	@Override
	public TxnStore getTxnStore() {
		return TransactionStorage.getTxnStore();
	}

	@Override
	public TxnSupplier getTxnSupplier() {
		return TransactionStorage.getTxnSupplier();
	}

    @Override
    public IgnoreTxnCacheSupplier getIgnoreTxnSupplier () {
        return TransactionStorage.getIgnoreTxnSupplier();
    }

	@Override
	public TransactionalRegion getTransactionalRegion(HRegion region) {
		return TransactionalRegions.get(region);
	}

	@Override
	public STransactionLib getTransactionLib() {
		return transactionLib;
	}

	@Override
	public TxnMessage.Txn getTransaction(long txnId, long beginTimestamp,
			long parentTxnId, long commitTimestamp,
			long globalCommitTimestamp, boolean hasAdditiveField,
			boolean additive, IsolationLevel isolationLevel, State state,
			String destTableBuffer) {
		return TxnMessage.Txn.newBuilder().setState(state.getId()).setCommitTs(commitTimestamp).setGlobalCommitTs(globalCommitTimestamp).setInfo(TxnMessage.TxnInfo.newBuilder().setTxnId(txnId).setBeginTs(beginTimestamp)
		.setParentTxnid(parentTxnId).setDestinationTables(ByteString.copyFrom(Bytes.toBytes(destTableBuffer))).setIsolationLevel(isolationLevel.encode()).build()).build();
	}

	@Override
	public void storeTransaction(RegionTxnStore regionTransactionStore,
			Txn transaction) throws IOException {
		regionTransactionStore.recordTransaction(transaction.getInfo());
		
	}

	@Override
	public long getTxnId(Txn transaction) {
		return transaction.getInfo().getTxnId();
	}

	@Override
	public byte[] transactionToByteArray(MultiFieldEncoder mfe, Txn transaction) {
		return transaction.toByteArray();
	}

}