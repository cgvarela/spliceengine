package com.splicemachine.derby.utils;

import java.sql.ResultSet;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.DriverManager;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.db.iapi.types.DataValueDescriptor;
import com.splicemachine.db.iapi.types.SQLBoolean;
import com.splicemachine.db.iapi.types.SQLInteger;
import com.splicemachine.db.impl.sql.execute.ValueRow;
import org.apache.hadoop.hbase.util.Bytes;
import com.splicemachine.derby.utils.marshall.DataHash;
import com.splicemachine.derby.utils.marshall.EntryDataHash;
import com.splicemachine.derby.utils.marshall.HashPrefix;
import com.splicemachine.derby.utils.marshall.KeyEncoder;
import com.splicemachine.derby.utils.marshall.KeyPostfix;
import com.splicemachine.derby.utils.marshall.NoOpDataHash;
import com.splicemachine.derby.utils.marshall.NoOpPostfix;
import com.splicemachine.derby.utils.marshall.SaltedPrefix;
import com.splicemachine.derby.utils.marshall.dvd.DescriptorSerializer;
import com.splicemachine.derby.utils.marshall.dvd.VersionedSerializers;
import com.splicemachine.hbase.KVPair;
import com.splicemachine.pipeline.api.CallBuffer;
import com.splicemachine.pipeline.impl.WriteCoordinator;
import com.splicemachine.si.api.txn.TxnView;
import com.splicemachine.si.impl.txn.ActiveWriteTxn;
import com.splicemachine.uuid.Snowflake;

// TODO: Convert this into a proper IT and/or UT to test SYSCS_START_CHILD_TRANSACTION procedure.

public class TransactionAdminTest {

    private static String sqlUpdate1 = "UPDATE customer SET status = 'false' WHERE cust_id = 3" ;
    private static String sqlUpdate2 = "UPDATE customer SET status = 'true' WHERE cust_id = 4";
    private static String sqlDummy = "SELECT * FROM SYSIBM.SYSDUMMY1";

    private static final String DB_CONNECTION = "jdbc:splice://localhost:1527/splicedb;user=splice;password=admin";
    private static final Snowflake snowflake = new Snowflake((short)1);

    private static KeyEncoder getKeyEncoder() throws StandardException {
        HashPrefix prefix;
        DataHash dataHash;
        KeyPostfix postfix = NoOpPostfix.INSTANCE;

        prefix = new SaltedPrefix(snowflake.newGenerator(100));
        dataHash = NoOpDataHash.INSTANCE;

        return new KeyEncoder(prefix,dataHash,postfix);
    }

    private static DataHash getRowHash() throws StandardException {
        //get all columns that are being set
        int[] columns = null;
        DataValueDescriptor dvds[] = new DataValueDescriptor[2];
        dvds[0] = new SQLInteger();
        dvds[1] = new SQLBoolean();

        DescriptorSerializer[] serializers = VersionedSerializers.forVersion("2.0",true).getSerializers(dvds);
        return new EntryDataHash(columns,null,serializers);
    }

    public static void main(String[] args) throws Exception {

        Connection conn1, conn2;
        ResultSet rs;
        PreparedStatement ps;
        /**
         *
         * starting parent transaction
         *
         */
        System.out.println("Starting parent transaction...");
        conn1 = DriverManager.getConnection(DB_CONNECTION, "splice", "admin");
        System.out.println("Connection class: " + conn1.getClass().getName());
        conn1.setAutoCommit(false);

        System.out.println("Fetching parent transaction id...");
        rs = conn1.createStatement().executeQuery("call SYSCS_UTIL.SYSCS_GET_CURRENT_TRANSACTION()");
        rs.next();
        long parentTransactionId = rs.getLong(1);
        long conglomId = SpliceAdmin.getConglomNumbers(conn1, "SPLICE", "customer")[0];
        /**
         *
         * elevating parent transaction
         *
         */
        ps = conn1.prepareStatement("call SYSCS_UTIL.SYSCS_ELEVATE_TRANSACTION(?)");
        ps.setString(1, "customer");
        ps.executeUpdate();

        System.out.println("Parent transaction id: " + parentTransactionId);

        System.out.println("Conglomerate id: " + conglomId);

        System.out.println("Starting child transaction...");

        /**
         *
         * starting child transaction
         *
         */
        conn2 = DriverManager.getConnection(DB_CONNECTION, null, null);
        conn2.setAutoCommit(false);
        ps = conn2.prepareStatement("call SYSCS_UTIL.SYSCS_START_CHILD_TRANSACTION(?,?)");
        ps.setLong(1, parentTransactionId);
        ps.setLong(2, conglomId);
        rs = ps.executeQuery();
        rs.next();
        long childTransactionId = rs.getLong(1);
        System.out.println("Child transaction id: " + childTransactionId);
        TxnView txn = new ActiveWriteTxn(childTransactionId,childTransactionId);
        System.out.println("Preparing query #2...");
        ps = conn2.prepareStatement(sqlUpdate2);
        System.out.println("Executing query #2...");
        int updated = ps.executeUpdate();
        System.out.println(updated + " rows updated.");
        //Configuration conf = new Configuration();

        CallBuffer<KVPair> callBuffer = WriteCoordinator.create(SpliceUtils.config).writeBuffer(Bytes.toBytes("1232"),
                txn, 1);

        ExecRow value = new ValueRow(2);
        DataValueDescriptor[] newData = new DataValueDescriptor[2];
        newData[0] = new SQLInteger(2);
        newData[1] = new SQLBoolean(false);

        value.setRowArray(newData);

        KeyEncoder keyEncoder = getKeyEncoder();
        byte[] key = keyEncoder.getKey(value);
        DataHash rowHash = getRowHash();

        rowHash.setRow(value);

        byte[] bdata = rowHash.encode();
        KVPair kv = new KVPair(key,bdata);
        //System.out.println("key:"+new String(key)+" value:"+new String(bdata));
        callBuffer.add(kv);

        System.out.println("committing child...");
        conn2.commit();
        callBuffer.close();
        System.out.println("Committing parent...");
        conn1.commit();

        System.out.println("Closing child...");
        conn2.close();

        System.out.println("Closing parent...");
        conn1.close();

        System.exit(0);
    }
}
