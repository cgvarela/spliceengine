package com.splicemachine.storage;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.utils.ByteSlice;

import java.util.Iterator;

/**
 *
 * Record Implementation
 *
 */
public interface Record<K,V> {
    /**
     *
     * Txn ID 1
     *
     * @return`
     */
    long getTxnId1();

    /**
     *
     * Txn ID 1
     *
     * @return
     */
    void setTxnId1(long transactionId1);

    /**
     *
     * Version Number of Updates, required due to
     * read committed nature of select for update functionality.
     *
     * @return
     */
    long getVersion();

    /**
     *
     * Version Number of Updates, required due to
     * read committed nature of select for update functionality.
     *
     * @return
     */
    void setVersion(long version);

    /**
     *
     * Tombstone Marker For Deletes
     *
     */
    boolean hasTombstone();

    /**
     *
     * Tombstone Marker For Deletes
     *
     */
    void setHasTombstone(boolean hasTombstone);


    /**
     *
     * Txn ID representing the parent timestamp of a hierarchical
     * transaction or the increment plus node id of a collapsible transaction.
     *
     * @return
     */
    long getTxnId2();


    /**
     *
     * Txn ID representing the parent timestamp of a hierarchical
     * transaction or the increment plus node id of a collapsible transaction.
     *
     * @return
     */
    void setTxnId2(long transactionId2);

    /**
     *
     * The effective commit timestamp of the data, -1 if rolledback
     *
     * @return
     */
    long getEffectiveTimestamp();

    /**
     *
     * The effective commit timestamp of the data, -1 if rolledback
     *
     * @return
     */
    void setEffectiveTimestamp(long effectiveTimestamp);


    /**
     *
     * Number of Columns
     *
     * @return
     */
    int numberOfColumns();

    /**
     *
     * Number of Columns
     *
     * @return
     */
    void setNumberOfColumns(int numberOfColumns);


    /**
     *
     * Set Actual Data
     *
     * @return
     */
    V getData(int[] columns, V row) throws StandardException;

    /**
     *
     * Set Actual Data
     *
     * @return
     */
    void setData(int[] columns, V row) throws StandardException;

    /**
     *
     * Set Actual Data
     *
     * @return
     */
    K getKey();

    /**
     *
     * Set Actual Data
     *
     * @return
     */
    void setKey(K key);

    RecordType getRecordType();

    void setRecordType(RecordType recordType);

    void setResolved(boolean resolved);

    boolean isResolved();

    void setActive(boolean active);

    boolean isActive();

    Record applyRollback(Iterator<Record<K,V>> recordIterator, ExecRow rowDefinition) throws StandardException;

    /**
     * Position 0 active Record, Position 1 redo record
     *
     * @param updatedRecord
     * @return
     */
    Record[] updateRecord(Record<K,V> updatedRecord, ExecRow recordDefinition) throws StandardException;

    int getSize();

    ByteSlice rowKeySlice();

    Record cancelToDelete();
}