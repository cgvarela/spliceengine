package com.splicemachine.si.data.light;

import com.splicemachine.si.impl.RowAccumulator;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class LRowAccumulator implements RowAccumulator<Map<String, Object>> {
    private Map<String, Object> accumulation = new HashMap<String, Object>();

    @Override
    public boolean isOfInterest(Map<String, Object> value) {
        return true;
    }

    @Override
    public boolean accumulate(Map<String, Object> packedRow) throws IOException {
        for (String k : packedRow.keySet()) {
            if (!accumulation.containsKey(k)) {
                accumulation.put(k, packedRow.get(k));
            }
        }
        return true;
    }

    @Override
    public boolean isFinished() {
        return false;
    }

    @Override
    public Map<String, Object> result() {
        return new HashMap<String, Object>(accumulation);
    }

}
