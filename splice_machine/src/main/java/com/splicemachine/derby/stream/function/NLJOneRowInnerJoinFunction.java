package com.splicemachine.derby.stream.function;

import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.derby.iapi.sql.execute.SpliceOperation;
import com.splicemachine.derby.impl.sql.execute.operations.JoinUtils;
import com.splicemachine.derby.impl.sql.execute.operations.LocatedRow;
import com.splicemachine.derby.stream.iapi.DataSet;
import com.splicemachine.derby.stream.iapi.OperationContext;
import com.splicemachine.derby.stream.utils.StreamUtils;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Collections;
import java.util.Iterator;

/**
 * Created by jleach on 4/24/15.
 */
public class NLJOneRowInnerJoinFunction<Op extends SpliceOperation> extends SpliceJoinFlatMapFunction<Op, LocatedRow, LocatedRow>  {

    public Iterator<LocatedRow> rightSideNLJIterator;
    public LocatedRow leftRow;

    public NLJOneRowInnerJoinFunction() {}

    public NLJOneRowInnerJoinFunction(OperationContext<Op> operationContext) {
        super(operationContext);
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        super.writeExternal(out);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        super.readExternal(in);
    }

    @Override
    public Iterable<LocatedRow> call(LocatedRow from) throws Exception {
        checkInit();
        leftRow = from;
        DataSet dataSet = null;
        try {
            op.getRightOperation().openCore(StreamUtils.controlDataSetProcessor);
            rightSideNLJIterator = op.getRightOperation().getLocatedRowIterator();

            if (rightSideNLJIterator.hasNext()) {
                LocatedRow rightRow = rightSideNLJIterator.next();
                ExecRow mergedRow = JoinUtils.getMergedRow(from.getRow(),
                        rightRow.getRow(), op.wasRightOuterJoin
                        , executionFactory.getValueRow(numberOfColumns));

                LocatedRow populatedRow = new LocatedRow(from.getRowLocation(),mergedRow);
                op.setCurrentLocatedRow(populatedRow);
                return Collections.singletonList(populatedRow);
            } else {
                return Collections.EMPTY_LIST;
            }
        } finally {
            if (op.getRightOperation()!= null)
                op.getRightOperation().close();
        }

    }

}