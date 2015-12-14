package com.splicemachine.derby.impl.sql.execute.actions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import com.splicemachine.db.impl.services.uuid.BasicUUID;
import com.splicemachine.ddl.DDLMessage.*;
import com.splicemachine.derby.ddl.DDLUtils;
import com.splicemachine.derby.impl.sql.execute.operations.scanner.TableScannerBuilder;
import com.splicemachine.derby.stream.function.KVPairFunction;
import com.splicemachine.derby.stream.function.RowTransformFunction;
import com.splicemachine.derby.stream.iapi.DataSet;
import com.splicemachine.derby.stream.iapi.DataSetProcessor;
import com.splicemachine.derby.stream.index.HTableWriterBuilder;
import com.splicemachine.derby.stream.utils.StreamUtils;
import com.splicemachine.protobuf.ProtoUtil;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.log4j.Logger;
import com.splicemachine.db.catalog.IndexDescriptor;
import com.splicemachine.db.catalog.UUID;
import com.splicemachine.db.catalog.types.IndexDescriptorImpl;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.reference.SQLState;
import com.splicemachine.db.iapi.services.io.FormatableBitSet;
import com.splicemachine.db.iapi.services.sanity.SanityManager;
import com.splicemachine.db.iapi.sql.Activation;
import com.splicemachine.db.iapi.sql.PreparedStatement;
import com.splicemachine.db.iapi.sql.ResultSet;
import com.splicemachine.db.iapi.sql.conn.LanguageConnectionContext;
import com.splicemachine.db.iapi.sql.depend.DependencyManager;
import com.splicemachine.db.iapi.sql.dictionary.ColumnDescriptor;
import com.splicemachine.db.iapi.sql.dictionary.ColumnDescriptorList;
import com.splicemachine.db.iapi.sql.dictionary.ConglomerateDescriptor;
import com.splicemachine.db.iapi.sql.dictionary.ConglomerateDescriptorList;
import com.splicemachine.db.iapi.sql.dictionary.ConstraintDescriptor;
import com.splicemachine.db.iapi.sql.dictionary.ConstraintDescriptorList;
import com.splicemachine.db.iapi.sql.dictionary.DataDescriptorGenerator;
import com.splicemachine.db.iapi.sql.dictionary.DataDictionary;
import com.splicemachine.db.iapi.sql.dictionary.IndexLister;
import com.splicemachine.db.iapi.sql.dictionary.IndexRowGenerator;
import com.splicemachine.db.iapi.sql.dictionary.SchemaDescriptor;
import com.splicemachine.db.iapi.sql.dictionary.TableDescriptor;
import com.splicemachine.db.iapi.sql.execute.ConstantAction;
import com.splicemachine.db.iapi.sql.execute.ExecIndexRow;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.db.iapi.store.access.ColumnOrdering;
import com.splicemachine.db.iapi.store.access.ConglomerateController;
import com.splicemachine.db.iapi.store.access.GroupFetchScanController;
import com.splicemachine.db.iapi.store.access.RowSource;
import com.splicemachine.db.iapi.store.access.TransactionController;
import com.splicemachine.db.iapi.types.DataValueDescriptor;
import com.splicemachine.db.iapi.types.RowLocation;
import com.splicemachine.db.impl.sql.execute.ColumnInfo;
import com.splicemachine.db.impl.sql.execute.IndexColumnOrder;
import com.splicemachine.derby.ddl.DDLChangeType;
import com.splicemachine.derby.impl.store.access.SpliceTransactionManager;
import com.splicemachine.derby.impl.store.access.hbase.HBaseRowLocation;
import com.splicemachine.derby.utils.DataDictionaryUtils;
import com.splicemachine.pipeline.exception.ErrorState;
import com.splicemachine.pipeline.exception.Exceptions;
import com.splicemachine.si.api.txn.Txn;
import com.splicemachine.si.api.txn.TxnLifecycleManager;
import com.splicemachine.si.api.txn.TxnView;
import com.splicemachine.si.impl.TransactionLifecycle;
import com.splicemachine.utils.SpliceLogUtils;

/**
 *	This class  describes actions that are ALWAYS performed for an
 *	ALTER TABLE Statement at Execution time.
 *
 */

public class AlterTableConstantOperation extends IndexConstantOperation {
    private static final Logger LOG = Logger.getLogger(AlterTableConstantOperation.class);
    // copied from constructor args and stored locally.
    protected SchemaDescriptor			sd;
    protected String						tableName;
    protected UUID						schemaId;
    protected ColumnInfo[]				columnInfo;
    protected ConstantAction[]	constraintActions;
    protected int						    behavior;
    protected String						indexNameForStatistics;
    private     int						    numIndexes;
    private     long[]					    indexConglomerateNumbers;
    private     ExecIndexRow[]			    indexRows;
    private	    GroupFetchScanController    compressHeapGSC;
    protected IndexRowGenerator[]		    compressIRGs;
    private     ColumnOrdering[][]		    ordering;
    private     int[][]		                collation;

    // CONSTRUCTORS

    /**
     *	Make the AlterAction for an ALTER TABLE statement.
     *
     * @param sd              descriptor for the table's schema.
     *  @param tableName          Name of table.
     * @param tableId            UUID of table
     * @param columnInfo          Information on all the columns in the table.
     * @param constraintActions  ConstraintConstantAction[] for constraints
     * @param lockGranularity      The lock granularity.
     * @param behavior            drop behavior for dropping column
     * @param indexNameForStatistics  Will name the index whose statistics
     */
    public AlterTableConstantOperation(
            SchemaDescriptor sd,
            String tableName,
            UUID tableId,
            ColumnInfo[] columnInfo,
            ConstantAction[] constraintActions,
            char lockGranularity,
            int behavior,
            String indexNameForStatistics) {
        super(tableId);
        if (LOG.isTraceEnabled())
            SpliceLogUtils.trace(LOG, "instantiating AlterTableConstantOperation for table {%s.%s} with ColumnInfo {%s} and constraintActions {%s}",sd!=null?sd.getSchemaName():"default",tableName, Arrays.toString(columnInfo), Arrays.toString(constraintActions));
        this.sd                     = sd;
        this.tableName              = tableName;
        this.columnInfo             = columnInfo;
        this.constraintActions      = constraintActions;
        this.behavior               = behavior;
        this.indexNameForStatistics = indexNameForStatistics;
        if (SanityManager.DEBUG)
            SanityManager.ASSERT(sd != null, "schema descriptor is null");
    }

    public	String	toString() {
        return "ALTER TABLE " + tableName;
    }

    /**
     * Run this constant action.
     *
     * @param activation the activation in which to run the action
     * @throws StandardException if an error happens during execution
     * of the action
     */
    @Override
    public void executeConstantAction(Activation activation) throws StandardException {
        SpliceLogUtils.trace(LOG, "executeConstantAction with activation %s",activation);

        executeConstantActionBody(activation);
    }

    /**
     * @see RowSource#getValidColumns
     */
    public FormatableBitSet getValidColumns() {
        SpliceLogUtils.trace(LOG, "getValidColumns");
        // All columns are valid
        return null;
    }

    /*private helper methods*/
    protected void executeConstantActionBody(Activation activation) throws StandardException {
        SpliceLogUtils.trace(LOG, "executeConstantActionBody with activation %s",activation);

        // Save references to the main structures we need.
        LanguageConnectionContext lcc = activation.getLanguageConnectionContext();
        DataDictionary dd = lcc.getDataDictionary();
        DependencyManager dm = dd.getDependencyManager();
        /*
        ** Inform the data dictionary that we are about to write to it.
        ** There are several calls to data dictionary "get" methods here
        ** that might be done in "read" mode in the data dictionary, but
        ** it seemed safer to do this whole operation in "write" mode.
        **
        ** We tell the data dictionary we're done writing at the end of
        ** the transaction.
        */
        dd.startWriting(lcc);

        // now do the real work

        // get an exclusive lock of the heap, to avoid deadlock on rows of
        // SYSCOLUMNS etc datadictionary tables and phantom table
        // descriptor, in which case table shape could be changed by a
        // concurrent thread doing add/drop column.

        // older version (or at target) has to get td first, potential deadlock
        TableDescriptor td = getTableDescriptor(lcc);

        dm.invalidateFor(td, DependencyManager.ALTER_TABLE, lcc);

        // Save the TableDescriptor off in the Activation
        activation.setDDLTableDescriptor(td);

		   /*
		    ** If the schema descriptor is null, then we must have just read
        ** ourselves in.  So we will get the corresponding schema descriptor 
        ** from the data dictionary.
		    */
        if (sd == null) {
            sd = getAndCheckSchemaDescriptor(dd, schemaId, "ALTER TABLE");
        }
		
		    /* Prepare all dependents to invalidate.  (This is their chance
		     * to say that they can't be invalidated.  For example, an open
		     * cursor referencing a table/view that the user is attempting to
		     * alter.) If no one objects, then invalidate any dependent objects.
		     */
        //TODO -sf- do we need to invalidate twice?
        dm.invalidateFor(td, DependencyManager.ALTER_TABLE, lcc);

        int numRows = 0;
        // adjust dependencies on user defined types
        adjustUDTDependencies(activation, columnInfo, false);
        executeConstraintActions(activation, numRows);
    }

    protected void executeConstraintActions(Activation activation, int numRows) throws StandardException {
        if(constraintActions==null || constraintActions.length == 0) return; //no constraints to apply, so nothing to do
        TransactionController tc = activation.getTransactionController();
        DataDictionary dd = activation.getLanguageConnectionContext().getDataDictionary();
        TableDescriptor td = activation.getDDLTableDescriptor();
        boolean tableScanned = numRows>=0;
        if(numRows<0)
            numRows = 0;

        List<CreateConstraintConstantOperation> fkConstraints = new ArrayList<>(constraintActions.length);
        for (ConstantAction ca : constraintActions) {
            if (ca instanceof CreateConstraintConstantOperation) {
                CreateConstraintConstantOperation cca = (CreateConstraintConstantOperation) ca;
                int constraintType = cca.getConstraintType();

              /* Some constraint types require special checking:
               *   Check		 - table must be empty, for now
               *   Primary Key - table cannot already have a primary key
               */
                switch (constraintType) {
                    case DataDictionary.PRIMARYKEY_CONSTRAINT:
                        // Check to see if a constraint of the same type
                        // already exists
                        ConstraintDescriptorList cdl = dd.getConstraintDescriptors(td);
                        if (cdl.getPrimaryKey() != null) {
                            throw StandardException.newException(SQLState.LANG_ADD_PRIMARY_KEY_FAILED1, td.getQualifiedName());
                        }

                        // create the PK constraint
                        createConstraint(activation, DDLChangeType.ADD_PRIMARY_KEY, "AddPrimaryKey", cca);

                        break;
                    case DataDictionary.UNIQUE_CONSTRAINT:
                        // create the unique constraint
                        createConstraint(activation, DDLChangeType.ADD_UNIQUE_CONSTRAINT, "AddUniqueConstraint", cca);

                        break;
                    case DataDictionary.FOREIGNKEY_CONSTRAINT:
                        // Do everything but FK constraints first, then FK constraints on 2nd pass.
                        fkConstraints.add(cca);
                        break;
                    case DataDictionary.CHECK_CONSTRAINT:
                        // create the check constraint
                        createCheckConstraint(activation, cca);
                        // Validate the constraint
                        ConstraintConstantOperation.validateConstraint(
                            cca.getConstraintName(), cca.getConstraintText(),
                            td, activation.getLanguageConnectionContext(), true);

                        break;
                }
            } else {
                // It's a DropConstraintConstantOperation (there are only two types)
                if (SanityManager.DEBUG) {
                    if (!(ca instanceof DropConstraintConstantOperation)) {
                        if (ca == null) {
                            SanityManager.THROWASSERT("constraintAction expected to be instanceof " +
                                                          "DropConstraintConstantOperation not null.");
                        }
                        assert ca != null;
                        SanityManager.THROWASSERT("constraintAction expected to be instanceof " +
                                "DropConstraintConstantOperation not " +ca.getClass().getName());
                    }
                }
                @SuppressWarnings("ConstantConditions") DropConstraintConstantOperation dcc = (DropConstraintConstantOperation)ca;

                if (dcc.getConstraintName() == null) {
                    // Sadly, constraintName == null is the only way to know if constraint op is drop PK
                    // Handle dropping Primary Key
                    executeDropPrimaryKey(activation, DDLChangeType.DROP_PRIMARY_KEY, "DropPrimaryKey", dcc);
                } else {
                    // Handle drop constraint
                    //noinspection ConstantConditions
                    executeDropConstraint(activation, DDLChangeType.DROP_CONSTRAINT, "DropConstraint", dcc);
                }
            }
        }

        // now handle all foreign key constraints
        for (ConstraintConstantOperation constraintAction : fkConstraints) {
            constraintAction.executeConstantAction(activation);
        }

   }

    private void createCheckConstraint(Activation activation, CreateConstraintConstantOperation newConstraint) throws StandardException {
        // Here we simply update the metadata - table descriptor and execute the new constraint creation
        List<String> constraintColumnNames = Arrays.asList(newConstraint.columnNames);
        if (constraintColumnNames.isEmpty()) {
            return;
        }

        LanguageConnectionContext lcc = activation.getLanguageConnectionContext();
        DataDictionary dd = lcc.getDataDictionary();
        TransactionController tc = lcc.getTransactionExecute();
        TableDescriptor tableDescriptor = activation.getDDLTableDescriptor();

        /*
         * Inform the data dictionary that we are about to write to it.
         * There are several calls to data dictionary "get" methods here
         * that might be done in "read" mode in the data dictionary, but
         * it seemed safer to do this whole operation in "write" mode.
         *
         * We tell the data dictionary we're done writing at the end of
         * the transaction.
         */
        dd.startWriting(lcc);

        // Get the properties on the old heap
        long oldCongNum = tableDescriptor.getHeapConglomerateId();

        /*
         * modify the conglomerate descriptor and add constraint
         */
        updateTableConglomerateDescriptor(tableDescriptor,
                oldCongNum,
                sd,
                lcc,
                newConstraint);
        // refresh the activation's TableDescriptor now that we've modified it
        activation.setDDLTableDescriptor(tableDescriptor);

        // follow thru with remaining constraint actions, create, store, etc.
        newConstraint.executeConstantAction(activation);

    }

    private void executeDropPrimaryKey(Activation activation, DDLChangeType changeType, String changeMsg,
                                       DropConstraintConstantOperation constraint) throws StandardException {
        LanguageConnectionContext lcc = activation.getLanguageConnectionContext();
        DataDictionary dd = lcc.getDataDictionary();
        TransactionController tc = lcc.getTransactionExecute();
        TableDescriptor tableDescriptor = activation.getDDLTableDescriptor();

        ConstraintDescriptor cd = dd.getConstraintDescriptors(tableDescriptor).getPrimaryKey();
        SanityManager.ASSERT(cd != null,tableDescriptor.getSchemaName()+"."+tableName+
                                                  " does not have a Primary Key to drop.");

        List<String> constraintColumnNames = Arrays.asList(cd.getColumnDescriptors().getColumnNames());

        ColumnDescriptorList columnDescriptorList = tableDescriptor.getColumnDescriptorList();
        int nColumns = columnDescriptorList.size();

        //   o create row template to tell the store what type of rows this
        //     table holds.
        //   o create array of collation id's to tell collation id of each
        //     column in table.
        ExecRow template = com.splicemachine.db.impl.sql.execute.RowUtil.getEmptyValueRow(columnDescriptorList.size(), lcc);

        TxnView parentTxn = ((SpliceTransactionManager)tc).getActiveStateTxn();

        // How were the columns ordered before?
        int[] oldColumnOrdering = DataDictionaryUtils.getColumnOrdering(parentTxn, tableId);

        // We're adding a uniqueness constraint. Column sort order will change.
        int[] collation_ids = new int[nColumns];
        ColumnOrdering[] columnSortOrder = new IndexColumnOrder[0];
        int j=0;
        for (int ix = 0; ix < nColumns; ix++) {
            ColumnDescriptor col_info = columnDescriptorList.get(ix);
            // Get a template value for each column
            if (col_info.getDefaultValue() != null) {
            /* If there is a default value, use it, otherwise use null */
                template.setColumn(ix + 1, col_info.getDefaultValue());
            }
            else {
                template.setColumn(ix + 1, col_info.getType().getNull());
            }
            // get collation info for each column.
            collation_ids[ix] = col_info.getType().getCollationType();
        }

        /*
         * Inform the data dictionary that we are about to write to it.
         * There are several calls to data dictionary "get" methods here
         * that might be done in "read" mode in the data dictionary, but
         * it seemed safer to do this whole operation in "write" mode.
         *
         * We tell the data dictionary we're done writing at the end of
         * the transaction.
         */
        dd.startWriting(lcc);

        // Get the properties on the old heap
        long oldCongNum = tableDescriptor.getHeapConglomerateId();
        ConglomerateController compressHeapCC =
            tc.openConglomerate(
                oldCongNum,
                false,
                TransactionController.OPENMODE_FORUPDATE,
                TransactionController.MODE_TABLE,
                TransactionController.ISOLATION_SERIALIZABLE);

        Properties properties = new Properties();
        compressHeapCC.getInternalTablePropertySet(properties);
        compressHeapCC.close();

        int tableType = tableDescriptor.getTableType();
        long newCongNum = tc.createConglomerate(
            "heap", // we're requesting a heap conglomerate
            template.getRowArray(), // row template
            columnSortOrder, //column sort order
            collation_ids,
            properties, // properties
            tableType == TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE ?
                (TransactionController.IS_TEMPORARY | TransactionController.IS_KEPT) :
                TransactionController.IS_DEFAULT);

        // follow thru with remaining constraint actions, create, store, etc.
        constraint.executeConstantAction(activation);

        /*
         * modify the conglomerate descriptor with the new conglomId
         */
        updateTableConglomerateDescriptor(tableDescriptor,
                                          newCongNum,
                                          sd,
                                          lcc,
                                          constraint);
        // refresh the activation's TableDescriptor now that we've modified it
        activation.setDDLTableDescriptor(tableDescriptor);

        // Start a tentative txn to demarcate the DDL change
        Txn tentativeTransaction;
        try {
            TxnLifecycleManager lifecycleManager = TransactionLifecycle.getLifecycleManager();
            tentativeTransaction =
                lifecycleManager.beginChildTransaction(parentTxn, Bytes.toBytes(Long.toString(oldCongNum)));
        } catch (IOException e) {
            LOG.error("Couldn't start transaction for tentative Drop Primary Key operation");
            throw Exceptions.parseException(e);
        }



        DDLChange ddlChange = ProtoUtil.createDropPKConstraint(tentativeTransaction.getTxnId(),
                newCongNum,oldCongNum,oldColumnOrdering,DataDictionaryUtils.getColumnOrdering(parentTxn, tableId),
                tableDescriptor.getColumnInfo(),lcc,(BasicUUID) tableId);

        // Initiate the copy from old conglomerate to new conglomerate and the interception
        // from old schema writes to new table with new column appended
        try {
            String schemaName = tableDescriptor.getSchemaName();
            String tableName = tableDescriptor.getName();

            DDLUtils.notifyMetadataChange(ddlChange);

            //wait for all past txns to complete
            Txn populateTxn = getChainedTransaction(tc, tentativeTransaction, oldCongNum, changeMsg+"(" +
                constraintColumnNames + ")");

            // Read from old conglomerate, transform each row and write to new conglomerate.
            transformAndWriteToNewConglomerate(activation, parentTxn, ddlChange, populateTxn.getBeginTimestamp());
            populateTxn.commit();
        } catch (IOException e) {
            throw Exceptions.parseException(e);
        }
        //notify other servers of the change
        DDLUtils.notifyMetadataChange(ddlChange);

    }

    private void executeDropConstraint(Activation activation,
                                       DDLChangeType changeType,
                                       String changeMsg,
                                       DropConstraintConstantOperation constraint) throws StandardException {
        LanguageConnectionContext lcc = activation.getLanguageConnectionContext();
        DataDictionary dd = lcc.getDataDictionary();
        TransactionController tc = lcc.getTransactionExecute();
        TableDescriptor tableDescriptor = activation.getDDLTableDescriptor();

        // try to find constraint descriptor using table's schema first
        SchemaDescriptor sd = tableDescriptor.getSchemaDescriptor();
        ConstraintDescriptor cd = dd.getConstraintDescriptorByName(tableDescriptor, sd,
                                                             constraint.getConstraintName(), true);
        if (cd == null) {
            // give it another shot since constraint may not be in the same schema as the table
            for (ConstraintDescriptor pcd : tableDescriptor.getConstraintDescriptorList()) {
                if (pcd.getSchemaDescriptor().getSchemaName().equals(constraint.getSchemaName()) &&
                    pcd.getConstraintName().equals(constraint.getSchemaName())) {
                    cd = pcd;
                    break;
                }
            }
        }
        SanityManager.ASSERT(cd != null,tableDescriptor.getSchemaName()+"."+tableName+
                                              " does not have a constraint to drop named "+constraint.getConstraintName());
        List<String> constraintColumnNames = Arrays.asList(cd.getColumnDescriptors().getColumnNames());

        TxnView parentTxn = ((SpliceTransactionManager)tc).getActiveStateTxn();

        // How were the columns ordered before?
        int[] oldColumnOrdering = DataDictionaryUtils.getColumnOrdering(parentTxn, tableId);

        /*
         * Inform the data dictionary that we are about to write to it.
         * There are several calls to data dictionary "get" methods here
         * that might be done in "read" mode in the data dictionary, but
         * it seemed safer to do this whole operation in "write" mode.
         *
         * We tell the data dictionary we're done writing at the end of
         * the transaction.
         */
        dd.startWriting(lcc);

        // Get the properties on the old heap
        long oldCongNum = tableDescriptor.getHeapConglomerateId();

        // refresh the activation's TableDescriptor now that we've modified it
        activation.setDDLTableDescriptor(tableDescriptor);

        // follow thru with remaining constraint actions, create, store, etc.
        constraint.executeConstantAction(activation);
        long indexConglomerateId = constraint.getIndexConglomerateId();

        // Start a tentative txn to demarcate the DDL change
        Txn tentativeTransaction;
        try {
            TxnLifecycleManager lifecycleManager = TransactionLifecycle.getLifecycleManager();
            tentativeTransaction =
                lifecycleManager.beginChildTransaction(parentTxn, Bytes.toBytes(Long.toString(oldCongNum)));
        } catch (IOException e) {
            LOG.error("Couldn't start transaction for tentative Drop Constraint operation");
            throw Exceptions.parseException(e);
        }
        String tableVersion = DataDictionaryUtils.getTableVersion(lcc, tableId);
        int[] newColumnOrdering = DataDictionaryUtils.getColumnOrdering(parentTxn, tableId);
        ColumnInfo[] newColumnInfo = tableDescriptor.getColumnInfo();

        DDLChange ddlChange = ProtoUtil.createTentativeDropConstraint(tentativeTransaction.getTxnId(),oldCongNum,
                indexConglomerateId,lcc,(BasicUUID) tableId);
        try {
            DDLUtils.notifyMetadataChangeAndWait(ddlChange);
            Txn populateTxn = getChainedTransaction(tc, tentativeTransaction, oldCongNum, changeMsg+"("+constraintColumnNames+")");
            populateTxn.commit();
        } catch (IOException e) {
            throw Exceptions.parseException(e);
        }

    }

    private void createConstraint(Activation activation,
                                  DDLChangeType changeType,
                                  String changeMsg,
                                  CreateConstraintConstantOperation newConstraint) throws StandardException {
        List<String> constraintColumnNames = Arrays.asList(newConstraint.columnNames);
        if (constraintColumnNames.isEmpty()) {
            return;
        }

        LanguageConnectionContext lcc = activation.getLanguageConnectionContext();
        DataDictionary dd = lcc.getDataDictionary();
        TransactionController tc = lcc.getTransactionExecute();
        TableDescriptor tableDescriptor = activation.getDDLTableDescriptor();
        ColumnDescriptorList columnDescriptorList = tableDescriptor.getColumnDescriptorList();
        int nColumns = columnDescriptorList.size();

        //   o create row template to tell the store what type of rows this
        //     table holds.
        //   o create array of collation id's to tell collation id of each
        //     column in table.
        ExecRow template = com.splicemachine.db.impl.sql.execute.RowUtil.getEmptyValueRow(columnDescriptorList.size(), lcc);

        TxnView parentTxn = ((SpliceTransactionManager)tc).getActiveStateTxn();

        // How were the columns ordered before?
        int[] oldColumnOrdering = DataDictionaryUtils.getColumnOrdering(parentTxn, tableId);

        // We're adding a uniqueness constraint. Column sort order will change.
        int[] collation_ids = new int[nColumns];
        ColumnOrdering[] columnSortOrder = new IndexColumnOrder[constraintColumnNames.size()];
        int j=0;
        for (int ix = 0; ix < nColumns; ix++) {
            ColumnDescriptor col_info = columnDescriptorList.get(ix);
            if (constraintColumnNames.contains(col_info.getColumnName())) {
                columnSortOrder[j++] = new IndexColumnOrder(ix);
            }
            // Get a template value for each column
            if (col_info.getDefaultValue() != null) {
            /* If there is a default value, use it, otherwise use null */
                template.setColumn(ix + 1, col_info.getDefaultValue());
            }
            else {
                template.setColumn(ix + 1, col_info.getType().getNull());
            }
            // get collation info for each column.
            collation_ids[ix] = col_info.getType().getCollationType();
        }

        /*
         * Inform the data dictionary that we are about to write to it.
         * There are several calls to data dictionary "get" methods here
         * that might be done in "read" mode in the data dictionary, but
         * it seemed safer to do this whole operation in "write" mode.
         *
         * We tell the data dictionary we're done writing at the end of
         * the transaction.
         */
        dd.startWriting(lcc);

        // Get the properties on the old heap
        long oldCongNum = tableDescriptor.getHeapConglomerateId();
        ConglomerateController compressHeapCC =
            tc.openConglomerate(
                oldCongNum,
                false,
                TransactionController.OPENMODE_FORUPDATE,
                TransactionController.MODE_TABLE,
                TransactionController.ISOLATION_SERIALIZABLE);

        Properties properties = new Properties();
        compressHeapCC.getInternalTablePropertySet(properties);
        compressHeapCC.close();

        int tableType = tableDescriptor.getTableType();
        long newCongNum = tc.createConglomerate(
            "heap", // we're requesting a heap conglomerate
            template.getRowArray(), // row template
            columnSortOrder, //column sort order - not required for heap
            collation_ids,
            properties, // properties
            tableType == TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE ?
                (TransactionController.IS_TEMPORARY | TransactionController.IS_KEPT) :
                TransactionController.IS_DEFAULT);

        /*
         * modify the conglomerate descriptor with the new conglomId
         */
        updateTableConglomerateDescriptor(tableDescriptor,
                                          newCongNum,
                                          sd,
                                          lcc,
                                          newConstraint);
        // refresh the activation's TableDescriptor now that we've modified it
        activation.setDDLTableDescriptor(tableDescriptor);

        // follow thru with remaining constraint actions, create, store, etc.
        newConstraint.executeConstantAction(activation);

        // Start a tentative txn to demarcate the DDL change
        Txn tentativeTransaction;
        try {
            TxnLifecycleManager lifecycleManager = TransactionLifecycle.getLifecycleManager();
            tentativeTransaction =
                lifecycleManager.beginChildTransaction(parentTxn, Bytes.toBytes(Long.toString(oldCongNum)));
        } catch (IOException e) {
            LOG.error("Couldn't start transaction for tentative Add Constraint operation");
            throw Exceptions.parseException(e);
        }
        String tableVersion = DataDictionaryUtils.getTableVersion(lcc, tableId);
        int[] newColumnOrdering = DataDictionaryUtils.getColumnOrdering(parentTxn, tableId);
        ColumnInfo[] newColumnInfo = tableDescriptor.getColumnInfo();

        DDLChange ddlChange = ProtoUtil.createTentativeAddConstraint(parentTxn.getTxnId(),oldCongNum,
                newCongNum,-1l,oldColumnOrdering,newColumnOrdering,newColumnInfo,lcc,(BasicUUID) tableId);

        try {
            DDLUtils.notifyMetadataChangeAndWait(ddlChange);
            //wait for all past txns to complete
            Txn populateTxn = getChainedTransaction(tc, tentativeTransaction, oldCongNum, changeMsg+"(" +
                constraintColumnNames + ")");

            // Read from old conglomerate, transform each row and write to new conglomerate.
            transformAndWriteToNewConglomerate(activation, parentTxn, ddlChange, populateTxn.getBeginTimestamp());
            populateTxn.commit();
        } catch (IOException e) {
            throw Exceptions.parseException(e);
        }

    }

    protected void updateAllIndexes(Activation activation,
                                    long newHeapConglom,
                                    TableDescriptor td,
                                    TransactionController tc) throws StandardException {
        /*
         * Update all of the indexes on a table when doing a bulk insert
         * on an empty table.
         */
        SpliceLogUtils.trace(LOG, "updateAllIndexes on new heap conglom %d",newHeapConglom);
        long[] newIndexCongloms = new long[numIndexes];
        for (int index = 0; index < numIndexes; index++) {
            updateIndex(newHeapConglom, activation, tc, td,index, newIndexCongloms);
        }
    }

    protected void updateIndex(long newHeapConglom, Activation activation,
                               TransactionController tc, TableDescriptor td, int index, long[] newIndexCongloms)
            throws StandardException {
        SpliceLogUtils.trace(LOG, "updateIndex on new heap conglom %d for index %d with newIndexCongloms %s",
                newHeapConglom, index, Arrays.toString(newIndexCongloms));

        // Get the ConglomerateDescriptor for the index
        ConglomerateDescriptor cd = td.getConglomerateDescriptor(indexConglomerateNumbers[index]);

        // Build the properties list for the new conglomerate
        ConglomerateController indexCC =
                tc.openConglomerate(
                        indexConglomerateNumbers[index],
                        false,
                        TransactionController.OPENMODE_FORUPDATE,
                        TransactionController.MODE_TABLE,
                        TransactionController.ISOLATION_SERIALIZABLE);
        Properties properties = getIndexProperties(newHeapConglom, index, cd, indexCC);


        // We can finally drain the sorter and rebuild the index
        // Populate the index.


        DataValueDescriptor[] rowArray = indexRows[index].getRowArray();
        ColumnOrdering[] columnOrder = ordering[index];
        int[] collationIds = collation[index];

        DataDictionary dd = activation.getLanguageConnectionContext().getDataDictionary();
        doIndexUpdate(dd,td,tc, index, newIndexCongloms, cd, properties, false,rowArray,columnOrder,collationIds);
        try{
            // Populate indexes
            IndexDescriptor indexDescriptor = cd.getIndexDescriptor();
            byte[] writeTable = Long.toString(newHeapConglom).getBytes();
            TxnView parentTxn = ((SpliceTransactionManager) tc).getActiveStateTxn();
            Txn tentativeTransaction;
            try {
                tentativeTransaction = TransactionLifecycle.getLifecycleManager().beginChildTransaction(parentTxn, Txn.IsolationLevel.SNAPSHOT_ISOLATION,writeTable);
            } catch (IOException e) {
                LOG.error("Couldn't start transaction for tentative DDL operation");
                throw Exceptions.parseException(e);
            }

            DDLChange ddlChange = ProtoUtil.createTentativeIndexChange(tentativeTransaction.getTxnId(), activation.getLanguageConnectionContext(),
                    newIndexCongloms[index], newHeapConglom, td, indexDescriptor);
            DDLUtils.notifyMetadataChangeAndWait(ddlChange);
            Txn indexTransaction = DDLUtils.getIndexTransaction(tc, tentativeTransaction, newHeapConglom,indexName);
             populateIndex(activation, indexTransaction, tentativeTransaction.getCommitTimestamp(), ddlChange.getTentativeIndex());
                //only commit the index transaction if the job actually completed
                indexTransaction.commit();
        } catch (Throwable t) {
         throw Exceptions.parseException(t);
        }

		/* Update the DataDictionary
		 *
		 * Update sys.sysconglomerates with new conglomerate #, we need to
		 * update all (if any) duplicate index entries sharing this same
		 * conglomerate.
		 */
        activation.getLanguageConnectionContext().getDataDictionary().updateConglomerateDescriptor(
                td.getConglomerateDescriptors(indexConglomerateNumbers[index]),
                newIndexCongloms[index],
                tc);

        // Drop the old conglomerate
        tc.dropConglomerate(indexConglomerateNumbers[index]);
    }

    protected void doIndexUpdate(DataDictionary dd,
                                TableDescriptor td,
                                TransactionController tc,
                                 int index, long[] newIndexCongloms,
                                 ConglomerateDescriptor cd, Properties properties,
                                 boolean statisticsExist,
                                 DataValueDescriptor[] rowArray,
                                 ColumnOrdering[] columnOrder,
                                 int[] collationIds) throws StandardException {

        newIndexCongloms[index] =
                tc.createAndLoadConglomerate(
                        "BTREE",
                        rowArray,
                        columnOrder,
                        collationIds,
                        properties,
                        TransactionController.IS_DEFAULT,
                        null,
                        null);

    }

    private Properties getIndexProperties(long newHeapConglom, int index, ConglomerateDescriptor cd, ConglomerateController indexCC) throws StandardException {
        // Get the properties on the old index
        Properties properties = new Properties();
        indexCC.getInternalTablePropertySet(properties);

		    /* Create the properties that language supplies when creating the
		     * the index.  (The store doesn't preserve these.)
		     */
        int indexRowLength = indexRows[index].nColumns();
        properties.put("baseConglomerateId", Long.toString(newHeapConglom));
        if (cd.getIndexDescriptor().isUnique()) {
            properties.put( "nUniqueColumns", Integer.toString(indexRowLength - 1));
        } else {
            properties.put( "nUniqueColumns", Integer.toString(indexRowLength));
        }
        if(cd.getIndexDescriptor().isUniqueWithDuplicateNulls()) {
            properties.put( "uniqueWithDuplicateNulls", Boolean.toString(true));
        }
        properties.put( "rowLocationColumn", Integer.toString(indexRowLength - 1));
        properties.put("nKeyFields", Integer.toString(indexRowLength));

        indexCC.close();
        return properties;
    }


    /**
     * Get info on the indexes on the table being compressed.
     *
     * @exception StandardException		Thrown on error
     */
    protected int getAffectedIndexes(TableDescriptor td) throws StandardException {
        SpliceLogUtils.trace(LOG, "getAffectedIndexes");

        IndexLister	indexLister = td.getIndexLister( );

		/* We have to get non-distinct index row generaters and conglom numbers
		 * here and then compress it to distinct later because drop column
		 * will need to change the index descriptor directly on each index
		 * entry in SYSCONGLOMERATES, on duplicate indexes too.
		 */
        compressIRGs = indexLister.getIndexRowGenerators();
        numIndexes = compressIRGs.length;
        indexConglomerateNumbers = indexLister.getIndexConglomerateNumbers();
        ExecRow emptyHeapRow = td.getEmptyExecRow();
        if(numIndexes > 0) {
            indexRows = new ExecIndexRow[numIndexes];
            ordering  = new ColumnOrdering[numIndexes][];
            collation = new int[numIndexes][];

            for (int index = 0; index < numIndexes; index++) {
                IndexRowGenerator curIndex = compressIRGs[index];
                RowLocation rl = new HBaseRowLocation(); //TODO -sf- don't explicitly depend on this
                // create a single index row template for each index
                indexRows[index] = curIndex.getIndexRowTemplate();
                curIndex.getIndexRow(emptyHeapRow,
                        rl,
                        indexRows[index],
                        null);
				        /* For non-unique indexes, we order by all columns + the RID.
				         * For unique indexes, we just order by the columns.
				         * No need to try to enforce uniqueness here as
				         * index should be valid.
				         */
                int[] baseColumnPositions = curIndex.baseColumnPositions();

                boolean[] isAscending = curIndex.isAscending();

                int numColumnOrderings = baseColumnPositions.length + 1;
                ordering[index] = new ColumnOrdering[numColumnOrderings];

                for (int ii =0; ii < numColumnOrderings - 1; ii++) {
                    ordering[index][ii] = new IndexColumnOrder(ii, isAscending[ii]);
                }
                ordering[index][numColumnOrderings - 1] = new IndexColumnOrder(numColumnOrderings - 1);
                collation[index] = curIndex.getColumnCollationIds(td.getColumnDescriptorList());
            }
        }
        return numIndexes;
    }

    protected TableDescriptor getTableDescriptor(LanguageConnectionContext lcc) throws StandardException {
        TableDescriptor td;
        td = DataDictionaryUtils.getTableDescriptor(lcc, tableId);
        if (td == null) {
            throw StandardException.newException(SQLState.LANG_TABLE_NOT_FOUND_DURING_EXECUTION, tableName);
        }
        return td;
    }

    protected static void executeUpdate(LanguageConnectionContext lcc, String updateStmt) throws StandardException {
        SpliceLogUtils.trace(LOG, "executeUpdate with statement {%s}", updateStmt);
        PreparedStatement ps = lcc.prepareInternalStatement(updateStmt);

        // This is a substatement; for now, we do not set any timeout
        // for it. We might change this behaviour later, by linking
        // timeout to its parent statement's timeout settings.
        ResultSet rs = ps.executeSubStatement(lcc, true, 0L);
        rs.close();
    }

    private void closeBulkFetchScan() throws StandardException {
        compressHeapGSC.close();
        compressHeapGSC = null;
    }

    /**
     * Do what's necessary to update a table descriptor that's been modified due to alter table
     * actions. Sometimes an action only requires changing the conglomerate number since a new
     * conglomerate has been created to replaced the old.  Other times the table descriptor's
     * main conglomerate descriptor needs to be dropped and recreated.
     * @param tableDescriptor the table descriptor that's being effected.
     * @param newConglomNum the conglomerate number of the conglomerate that's been created to
     *                      replace the old.
     * @param sd descriptor for the schema in which the new conglomerate lives.
     * @param lcc language connection context required for coordination.
     * @param constraintAction constraint being created, dropped, etc
     * @return the modified conglomerate descriptor.
     * @throws StandardException
     */
    protected ConglomerateDescriptor updateTableConglomerateDescriptor(TableDescriptor tableDescriptor,
                                                     long newConglomNum,
                                                     SchemaDescriptor sd,
                                                     LanguageConnectionContext lcc,
                                                     ConstraintConstantOperation constraintAction) throws StandardException {

        // We invalidate all statements that use the old conglomerates
        TransactionController tc = lcc.getTransactionExecute();
        DataDictionary dd = lcc.getDataDictionary();
        DataDescriptorGenerator ddg = dd.getDataDescriptorGenerator();

        dd.getDependencyManager().invalidateFor(tableDescriptor, DependencyManager.ALTER_TABLE, lcc);

        ConglomerateDescriptor modifiedConglomerateDescriptor = null;

        if(constraintAction.getConstraintType()== DataDictionary.PRIMARYKEY_CONSTRAINT) {
            // Adding a PK - need to drop the old conglomerate descriptor and
            // create a new one with an IndexRowGenerator to replace the old

            // get the column positions for the new PK to
            // create the PK IndexDescriptor and new conglomerate
            int [] pkColumns =
                ((CreateConstraintConstantOperation)constraintAction).genColumnPositions(tableDescriptor, true);
            boolean[] ascending = new boolean[pkColumns.length];
            for(int i=0;i<ascending.length;i++){
                ascending[i] = true;
            }
            IndexDescriptor indexDescriptor =
                new IndexDescriptorImpl("PRIMARYKEY",true,false,pkColumns,ascending,pkColumns.length);
            IndexRowGenerator irg = new IndexRowGenerator(indexDescriptor);

            // Replace old table conglomerate with new one with the new PK conglomerate
            ConglomerateDescriptorList conglomerateList = tableDescriptor.getConglomerateDescriptorList();
            modifiedConglomerateDescriptor =
                conglomerateList.getConglomerateDescriptor(tableDescriptor.getHeapConglomerateId());

            dd.dropConglomerateDescriptor(modifiedConglomerateDescriptor, tc);
            conglomerateList.dropConglomerateDescriptor(tableDescriptor.getUUID(), modifiedConglomerateDescriptor);

            // Create a new conglomerate descriptor with new IndexRowGenerator
            ConglomerateDescriptor cgd = ddg.newConglomerateDescriptor(newConglomNum,
                                                                       null,//modifiedConglomerateDescriptor.getConglomerateName(),
                                                                       false,
                                                                       irg,
                                                                       false,
                                                                       null,
                                                                       tableDescriptor.getUUID(),
                                                                       sd.getUUID());
            dd.addDescriptor(cgd, sd, DataDictionary.SYSCONGLOMERATES_CATALOG_NUM, false, tc);

            // add the newly crated conglomerate to the table descriptor
            conglomerateList.add(cgd);

            // update the conglomerate in the data dictionary so that, when asked for TableDescriptor,
            // it's built with the new PK conglomerate
            ConglomerateDescriptor[] conglomerateArray = conglomerateList.getConglomerateDescriptors(newConglomNum);
            dd.updateConglomerateDescriptor(conglomerateArray, newConglomNum, tc);
        } else //noinspection StatementWithEmptyBody
            if (constraintAction.getConstraintType()== DataDictionary.UNIQUE_CONSTRAINT) {
                // Do nothing for creating unique constraint. Index creation will handle
        } else if (constraintAction.getConstraintType() == DataDictionary.DROP_CONSTRAINT) {
            if (constraintAction.getConstraintName() == null) {
                // Unfortunately, this is the only way we have to know if we're dropping a PK
                long heapConglomId = tableDescriptor.getHeapConglomerateId();
                ConglomerateDescriptorList conglomerateList = tableDescriptor.getConglomerateDescriptorList();
                modifiedConglomerateDescriptor =
                    conglomerateList.getConglomerateDescriptor(heapConglomId);

                dd.dropConglomerateDescriptor(modifiedConglomerateDescriptor, tc);
                conglomerateList.dropConglomerateDescriptor(tableDescriptor.getUUID(), modifiedConglomerateDescriptor);

                // Create a new conglomerate descriptor
                ConglomerateDescriptor cgd = ddg.newConglomerateDescriptor(newConglomNum,
                                                                           null,//modifiedConglomerateDescriptor.getConglomerateName(),
                                                                           false,
                                                                           null,
                                                                           false,
                                                                           null,
                                                                           tableDescriptor.getUUID(),
                                                                           sd.getUUID());
                dd.addDescriptor(cgd, sd, DataDictionary.SYSCONGLOMERATES_CATALOG_NUM, false, tc);

                // add the newly crated conglomerate to the table descriptor
                conglomerateList.add(cgd);

                // update the conglomerate in the data dictionary so that, when asked for TableDescriptor,
                // it's built with the new PK conglomerate
                ConglomerateDescriptor[] conglomerateArray = conglomerateList.getConglomerateDescriptors(newConglomNum);
                dd.updateConglomerateDescriptor(conglomerateArray, newConglomNum, tc);
            }
        }
        return modifiedConglomerateDescriptor;
    }


    protected Txn getChainedTransaction(TransactionController tc,
                                      Txn txnToWaitFor,
                                      long tableConglomId,
                                      String alterTableActionName)
        throws StandardException {
        final TxnView wrapperTxn = ((SpliceTransactionManager)tc).getActiveStateTxn();

        /*
         * We have an additional waiting transaction that we use to ensure that all elements
         * which commit after the demarcation point are committed BEFORE the populate part.
         */
        byte[] tableBytes = Long.toString(tableConglomId).getBytes();
        Txn waitTxn;
        try{
            waitTxn =
                TransactionLifecycle.getLifecycleManager().chainTransaction(wrapperTxn,
                                                                            Txn.IsolationLevel.SNAPSHOT_ISOLATION,
                                                                            false,tableBytes,txnToWaitFor);
        }catch(IOException ioe){
            LOG.error("Could not create a wait transaction",ioe);
            throw Exceptions.parseException(ioe);
        }

        //get the absolute user transaction
        TxnView uTxn = wrapperTxn;
        TxnView n = uTxn.getParentTxnView();
        while(n.getTxnId()>=0){
            uTxn = n;
            n = n.getParentTxnView();
        }
        // Wait for past transactions to complete
        long oldestActiveTxn;
        try {
            oldestActiveTxn = waitForConcurrentTransactions(waitTxn, uTxn,tableConglomId);
        } catch (IOException e) {
            LOG.error("Unexpected error while waiting for past transactions to complete", e);
            throw Exceptions.parseException(e);
        }
        if (oldestActiveTxn>=0) {
            throw ErrorState.DDL_ACTIVE_TRANSACTIONS.newException(alterTableActionName,oldestActiveTxn);
        }
        Txn populateTxn;
        try{
            /*
             * We need to make the populateTxn a child of the wrapper, so that we can be sure
             * that the write pipeline is able to see the conglomerate descriptor. However,
             * this makes the SI logic more complex during the populate phase.
             */
            populateTxn = TransactionLifecycle.getLifecycleManager().chainTransaction(
                wrapperTxn, Txn.IsolationLevel.SNAPSHOT_ISOLATION, true, tableBytes,waitTxn);
        } catch (IOException e) {
            LOG.error("Couldn't commit transaction for tentative DDL operation");
            // TODO cleanup tentative DDL change?
            throw Exceptions.parseException(e);
        }
        return populateTxn;
    }


    protected void transformAndWriteToNewConglomerate(Activation activation,
                                                      TxnView parentTxn,
                                                      DDLChange ddlChange,
                                                      long demarcationPoint) throws IOException, StandardException {

        Txn childTxn = beginChildTransaction(parentTxn, ddlChange.getTxnId());
        // create a scanner to scan old conglomerate
        TableScannerBuilder tableScannerBuilder = createTableScannerBuilder(childTxn, demarcationPoint, ddlChange);
        long baseConglomerateNumber = ddlChange.getTentativeIndex().getTable().getConglomerate();
        DataSetProcessor dsp = StreamUtils.sparkDataSetProcessor;
        StreamUtils.setupSparkJob(dsp, activation, this.toString(), "admin");
        DataSet dataSet = dsp.getTableScanner(activation, tableScannerBuilder, new Long(baseConglomerateNumber).toString());

        //Create table writer for new conglomerate
        HTableWriterBuilder tableWriter = createTableWriterBuilder(childTxn, ddlChange);

        dataSet.map(new RowTransformFunction(ddlChange)).index(new KVPairFunction()).writeKVPair(tableWriter);

        childTxn.commit();
    }

    // Create a table writer to wrte KVPairs to new conglomerate, skipping index writing.
    private HTableWriterBuilder createTableWriterBuilder(TxnView txn, DDLChange ddlChange) {
        HTableWriterBuilder tableWriterBuilder = new HTableWriterBuilder()
                .txn(txn)
                .skipIndex(true)
                .heapConglom(ddlChange.getTentativeIndex().getIndex().getConglomerate());

        return tableWriterBuilder;

    }

    // Create a table scanner for old conglomerate. Make sure to pass in demarcation point to pick up writes before it.
    private TableScannerBuilder createTableScannerBuilder(Txn txn, long demarcationPoint, DDLChange ddlChange) throws IOException{

        TableScannerBuilder builder =
                new TableScannerBuilder()
                        .scan(DDLUtils.createFullScan())
                        .transaction(txn)
                        .demarcationPoint(demarcationPoint);

       // TransformingDDLDescriptor tdl = (TransformingDDLDescriptor) ddlChange.getTentativeDDLDesc();
       // tdl.setScannerBuilderProperties(builder);
        return builder;
    }
}
