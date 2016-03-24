package com.splicemachine.pipeline.api;


import com.splicemachine.metrics.Metrics;
import com.splicemachine.metrics.TimeView;

/**
 * @author Scott Fines
 *         Date: 2/5/14
 */
public interface WriteStats {
		WriteStats NOOP_WRITE_STATS = new WriteStats() {
				@Override public long getBytesWritten() { return 0; }
				@Override public long getRowsWritten() { return 0; }
				@Override public long getTotalRetries() { return 0; }
				@Override public long getGlobalErrors() { return 0; }
				@Override public long getPartialFailureCount() { return 0; }
				@Override public long getRejectedCount() { return 0; }
				@Override public TimeView getSleepTime() { return Metrics.noOpTimeView(); }
				@Override public TimeView getNetworkTime() { return Metrics.noOpTimeView(); }
				@Override public TimeView getTotalTime() { return Metrics.noOpTimeView(); }
		};

		long getBytesWritten();

		long getRowsWritten();

		long getTotalRetries();

		long getGlobalErrors();

		long getPartialFailureCount();

		long getRejectedCount();

		TimeView getSleepTime();

		TimeView getNetworkTime();

		TimeView getTotalTime();
}