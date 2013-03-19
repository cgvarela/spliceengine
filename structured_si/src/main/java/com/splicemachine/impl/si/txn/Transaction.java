package com.splicemachine.impl.si.txn;

import java.io.IOException;

import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.log4j.Logger;

import com.google.common.cache.Cache;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.splicemachine.iapi.txn.TransactionState;
import com.splicemachine.si.utils.SIConstants;
import com.splicemachine.si.utils.SIUtils;
import com.splicemachine.utils.SpliceLogUtils;

public class Transaction extends SIConstants {
	private static Logger LOG = Logger.getLogger(Transaction.class);
	protected static HashFunction hashFunction = Hashing.murmur3_128();
	protected Long startTimestamp;
    protected Long commitTimestamp;
    protected TransactionState transactionState;

    public Transaction() {
    	super();
    }

    public Transaction(Long startTimestamp) {
    	super();
    	this.startTimestamp = startTimestamp;
    }
    
    public Transaction(Long startTimestamp, TransactionState transactionState) {
    	super();
    	this.startTimestamp = startTimestamp;
    	this.transactionState = transactionState;
    }

	public long getStartTimestamp() {
		return startTimestamp;
	}

	public void setStartTimestamp(Long startTimestamp) {
		this.startTimestamp = startTimestamp;
	}

	public Long getCommitTimestamp() {
		return commitTimestamp;
	}

	public void setCommitTimestamp(Long commitTimestamp) {
		this.commitTimestamp = commitTimestamp;
	}

	public TransactionState getTransactionState() {
		return transactionState;
	}

	public void setTransactionState(TransactionState transactionState) {
		this.transactionState = transactionState;
	}

	@Override
    public String toString() {
        return String.format("transactionStartTimeStamp %d, " +
        		"transactionCommitTimestamp %d, transactionStatus %s",
        		startTimestamp,commitTimestamp,transactionState);	
	}
	
	public int prepareCommit() {
		SpliceLogUtils.trace(LOG, "prepareCommit %s",this);
		return 0;
	}

	public void doCommit(long commitTimestamp) {
		SpliceLogUtils.trace(LOG, "doCommit %d",commitTimestamp);
    	this.commitTimestamp = commitTimestamp;
    	this.transactionState = TransactionState.COMMIT;
    	write();
	}
	
	public void abort() {
		SpliceLogUtils.trace(LOG, "doAbort %s",this);
    	this.transactionState = TransactionState.ABORT;
    	write();
	}
	public void write() {
		SpliceLogUtils.trace(LOG, "write %s",this);
		HTableInterface table = null;
		try {
			table = SIUtils.pushTransactionTable();
			Put put = new Put(Bytes.toBytes(startTimestamp));
			put.add(TRANSACTION_FAMILY_BYTES, TRANSACTION_START_TIMESTAMP_COLUMN_BYTES,Bytes.toBytes(startTimestamp));
			if (commitTimestamp != null)
				put.add(TRANSACTION_FAMILY_BYTES, TRANSACTION_COMMIT_TIMESTAMP_COLUMN_BYTES,Bytes.toBytes(commitTimestamp));
			put.add(TRANSACTION_FAMILY_BYTES, TRANSACTION_STATUS_COLUMN_BYTES,Bytes.toBytes(transactionState.ordinal()));
			table.put(put);
		} catch (Exception e) {
			SpliceLogUtils.logAndThrowRuntime(LOG, e);			
		} finally {
			if (table != null)
				try {
					table.close();
				} catch (IOException e) {
					SpliceLogUtils.logAndThrowRuntime(LOG, e);
				}
		}
	}
	/**
	 * Transaction with status ABORT, ERROR, or COMMIT.  Active transactions are mutable so they cannot be cached.
	 * 
	 * @return boolean
	 */
	protected boolean isCacheable() {
		return transactionState.equals(TransactionState.ABORT) || transactionState.equals(TransactionState.ERROR) || transactionState.equals(TransactionState.COMMIT);
	}
	
	public static Transaction readTransaction(long startTimestamp, Cache<Long,Transaction> transactionCache) {
		SpliceLogUtils.trace(LOG, "readTransaction %d",startTimestamp);
		Transaction transaction;
		if ( transactionCache != null && (transaction = transactionCache.getIfPresent(startTimestamp)) !=null) // Cache Hit
			return transaction;
		transaction = new Transaction(startTimestamp);
		transaction.read();
		if (transactionCache != null && transaction.isCacheable())
			transactionCache.put(transaction.startTimestamp, transaction);
		return transaction;
	}

	public static Transaction readTransaction(long startTimestamp) {
		SpliceLogUtils.trace(LOG, "readTransaction %d",startTimestamp);
		Transaction transaction = new Transaction(startTimestamp);
		transaction.read();
		return transaction;
	}

	public void read() {
		SpliceLogUtils.trace(LOG, "read %s",this);
		HTableInterface table = null;
		try {
			table = SIUtils.pushTransactionTable();
			Get get = new Get(Bytes.toBytes(startTimestamp));
			Result result = table.get(get);
			byte[] value = result.getValue(TRANSACTION_FAMILY_BYTES, TRANSACTION_COMMIT_TIMESTAMP_COLUMN_BYTES);
			if (value != null)	
				this.commitTimestamp = Bytes.toLong(value);
			value = result.getValue(TRANSACTION_FAMILY_BYTES, TRANSACTION_STATUS_COLUMN_BYTES);
			if (value != null)	
				this.transactionState = TransactionState.values()[Bytes.toInt(value)];
		} catch (Exception e) {
			SpliceLogUtils.logAndThrowRuntime(LOG, e);			
		} finally {
			if (table != null)
				try {
					table.close();
				} catch (IOException e) {
					SpliceLogUtils.logAndThrowRuntime(LOG, e);
				}
		}
	}

	@Override
	public boolean equals(Object object) {
		Transaction transaction = (Transaction) object;
		if (object != null && transaction.commitTimestamp == commitTimestamp && 
				transaction.startTimestamp == startTimestamp && 
				transaction.transactionState == transactionState)
			return true;
		return false;
	}
	
}
