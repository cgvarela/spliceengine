package com.splicemachine.pipeline.callbuffer;

import com.splicemachine.hbase.KVPair;
import com.splicemachine.pipeline.api.CallBuffer;
import com.splicemachine.pipeline.api.PreFlushHook;
import com.splicemachine.pipeline.api.WriteConfiguration;
import com.splicemachine.si.api.txn.TxnView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * a Thread-safe write buffer, for when many threads need to write to a single location (useful
 * for background tasks and other stuff like that).
 *
 * The basic structure is a queue which acts as a concurrent buffer. When the queue fills up
 * to a user-specified maximum, then it is flushed asynchronously. To avoid having to deal
 * with all the correctness issues incumbent in writing a from-scratch table-writing buffer,
 * we just delegate our behavior to a prior-created buffer. However, as most buffers are not
 * thread-safe, we must impose additional synchronization, which we do during the flushBuffer()
 * stage.
 *
 * Thus, this algorithm blocks only during the buffer flushing phase, and only long enough to safely
 * flush. Adding elements to the queue is non-blocking.
 *
 * @author Scott Fines
 *         Date: 1/21/14
 */
public class ConcurrentWriteBuffer implements CallBuffer<KVPair> {
    private final ConcurrentLinkedQueue<KVPair> queue;
    private final int maxEntries;

    private final CallBuffer<KVPair> delegateBuffer;
    private volatile boolean closed = false;
    private final AtomicBoolean flushing = new AtomicBoolean(false);

    public ConcurrentWriteBuffer(int maxEntries, CallBuffer<KVPair> delegateBuffer) {
        this.maxEntries = maxEntries;
        this.queue = new ConcurrentLinkedQueue<>();
        this.delegateBuffer = delegateBuffer;
    }

    @Override
    public void add(KVPair element) throws Exception {
        assert !closed : "Incorrectly adding to closed buffer!";
        if (closed) return;
        queue.offer(element);
        if (queue.size() > maxEntries) {
            flushBuffer();
        }
    }

    @Override
    public void addAll(KVPair[] elements) throws Exception {
        assert !closed : "Incorrectly adding to closed buffer!";
        if (closed) return;
        for (KVPair kvPair : elements) {
            queue.offer(kvPair);
        }
        if (queue.size() > maxEntries)
            flushBuffer();
    }

		@Override
		public void addAll(Iterable<KVPair> elements) throws Exception {
				assert !closed :"Incorrectly adding to closed buffer!";
				if(closed) return;

				for(KVPair element:elements){
						queue.offer(element);
				}
				if(queue.size()>maxEntries)
						flushBuffer();
		}

    @Override
    public void flushBuffer() throws Exception {
        if (closed) return;
        if (!flushing.compareAndSet(false, true)) return; //someone else is already flushing, so don't bother
        try {

            int size = queue.size();
            if (size <= 0) return; //nothing to do if we don't have anything to flush

						Collection<KVPair> pairs = new ArrayList<>(size);
						for(int i=0;i<size;i++){
								KVPair next = queue.poll();
								if(next==null) break;
								pairs.add(next);
						}

            if (pairs.size() <= 0) return; //nothing to do now if there's nothing to flush

            synchronized (delegateBuffer) {
                delegateBuffer.addAll(pairs);
                delegateBuffer.flushBuffer();
            }
        } finally {
            //allow other flushes to proceed.
            flushing.set(false);
        }
    }

    @Override
    public void flushBufferAndWait() throws Exception {
        throw new UnsupportedOperationException("");
    }

    @Override
		public void close() throws Exception {
				closed=true;
				synchronized (delegateBuffer){
						delegateBuffer.flushBuffer();
						delegateBuffer.close();
				}
		}

		@Override
		public PreFlushHook getPreFlushHook() {
			return delegateBuffer.getPreFlushHook();
		}

		@Override
		public WriteConfiguration getWriteConfiguration() {
			return delegateBuffer.getWriteConfiguration();
		}
        @Override public TxnView getTxn() { return delegateBuffer.getTxn();}
}