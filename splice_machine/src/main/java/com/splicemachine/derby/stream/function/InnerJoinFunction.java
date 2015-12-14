package com.splicemachine.derby.stream.function;

import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.derby.iapi.sql.execute.SpliceOperation;
import com.splicemachine.derby.impl.sql.execute.operations.JoinUtils;
import com.splicemachine.derby.impl.sql.execute.operations.LocatedRow;
import com.splicemachine.derby.stream.iapi.OperationContext;
import scala.Tuple2;

import javax.annotation.concurrent.NotThreadSafe;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

/**
 *
 */
@NotThreadSafe
public class InnerJoinFunction<Op extends SpliceOperation> extends SpliceJoinFunction<Op, Tuple2<ExecRow,Tuple2<LocatedRow,LocatedRow>>, LocatedRow> {
    private static final long serialVersionUID = 3988079974858059941L;
    public InnerJoinFunction() {
    }

    public InnerJoinFunction(OperationContext<Op> operationContext) {
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
    public LocatedRow call(Tuple2<ExecRow, Tuple2<LocatedRow, LocatedRow>> tuple) throws Exception {
        checkInit();
        ExecRow execRow = JoinUtils.getMergedRow(tuple._2()._1().getRow(),tuple._2()._2().getRow(),
                op.wasRightOuterJoin,executionFactory.getValueRow(numberOfColumns));
        LocatedRow lr = new LocatedRow(tuple._2._1.getRowLocation(),execRow);
        op.setCurrentLocatedRow(lr);
        return lr;
    }
}
