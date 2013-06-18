package com.splicemachine.si.impl;

import com.splicemachine.si.data.api.SDataLib;

public class DecodedKeyValue<Data, Result, KeyValue, Put, Delete, Get, Scan, OperationWithAttributes, Lock> {
    private final SDataLib<Data, Result, KeyValue, OperationWithAttributes, Put, Delete, Get, Scan, Lock> dataLib;

    private KeyValue keyValue;

    private Data row;
    private Data family;
    private Data qualifier;
    private Data value;
    private Long timestamp;

    public DecodedKeyValue(SDataLib dataLib) {
        this.dataLib = dataLib;
    }

    public void setKeyValue(KeyValue keyValue) {
        if (keyValue.getClass().equals(DecodedKeyValue.class)) {
            throw new RuntimeException("fail");
        }
        this.row = null;
        this.family = null;
        this.qualifier = null;
        this.value = null;
        this.timestamp = null;
        this.keyValue = keyValue;
    }

    public KeyValue keyValue() {
        return keyValue;
    }

    public Data row() {
        if (row == null) {
            row = dataLib.getKeyValueRow(keyValue);
        }
        return row;
    }

    public Data family() {
        if (family == null) {
            family = dataLib.getKeyValueFamily(keyValue);
        }
        return family;
    }

    public Data qualifier() {
        if (qualifier == null) {
            qualifier = dataLib.getKeyValueQualifier(keyValue);
        }
        return qualifier;
    }

    public Data value() {
        if (value == null) {
            value = dataLib.getKeyValueValue(keyValue);
        }
        return value;
    }

    public long timestamp() {
        if (timestamp == null) {
            timestamp = dataLib.getKeyValueTimestamp(keyValue);
        }
        return timestamp;
    }
}
