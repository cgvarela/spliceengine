package com.splicemachine.db.impl.ast;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.sql.compile.CostEstimate;
import com.splicemachine.db.iapi.sql.compile.Visitable;
import com.splicemachine.db.impl.sql.compile.*;
import org.apache.log4j.Logger;

/**
 *
 * This visitor will push down limits and offsets down to determine costs
 * at the appropriate level.
 *
 */
public class LimitOffsetVisitor extends AbstractSpliceVisitor {
    private static Logger LOG=Logger.getLogger(LimitOffsetVisitor.class);
    public long offset = -1;
    public long fetchFirst = -1;
    public double scaleFactor;
    /**
     *
     * Visiting the RowCountNode
     *
     * @param node
     * @return
     * @throws StandardException
     */
    @Override
    public Visitable visit(RowCountNode node) throws StandardException {
        offset = fetchNumericValue(node.offset);
        fetchFirst = fetchNumericValue(node.fetchFirst);
        scaleFactor = 1.0d;
        if (LOG.isDebugEnabled())
            LOG.debug(String.format("rowCountNode ={%s}, offset=%s, fetchFirst=%s", node, offset, fetchFirst));
        return super.visit(node);
    }

    private long fetchNumericValue(ValueNode valueNode) throws StandardException {
        if (valueNode != null && valueNode instanceof NumericConstantNode)
            return ((NumericConstantNode)valueNode).getValue().getLong();
        return -1l;
    }

    /**
     * Adjusts the base table cost based on the limit, removes limit elements
     *
     * @param node
     * @return
     * @throws StandardException
     */
    @Override
    public Visitable visit(IndexToBaseRowNode node) throws StandardException {
        adjustBaseTableCost(node);
        nullify();
        return super.visit(node);
    }
    /**
     * Adjusts the base table cost based on the limit, removes limit elements
     *
     * @param node
     * @return
     * @throws StandardException
     */

    @Override
    public Visitable visit(FromBaseTable node) throws StandardException {
        if (!node.isDistinctScan())
            adjustBaseTableCost(node);
        else
            adjustCost(node);
        nullify();
        return super.visit(node);
    }
    /**
     * Adjusts the remote rows and cost based on the limit, removes limit elements
     *
     * @param node
     * @return
     * @throws StandardException
     */

    @Override
    public Visitable visit(DistinctNode node) throws StandardException {
        adjustCost(node);
        nullify();
        return super.visit(node);
    }


    @Override
    public Visitable visit(FromSubquery node) throws StandardException {
        nullify();
        return super.visit(node);
    }
    /**
     * Adjusts the remote rows and cost based on the limit, removes limit elements
     *
     * @param node
     * @return
     * @throws StandardException
     */

    @Override
    public Visitable visit(GroupByNode node) throws StandardException {
        adjustCost(node);
        nullify();
        return super.visit(node);
    }
    /**
     * Adjusts the remote rows and cost based on the limit, removes limit elements
     *
     * @param node
     * @return
     * @throws StandardException
     */

    @Override
    public Visitable visit(JoinNode node) throws StandardException {
        adjustCost(node);
        nullify();
        return super.visit(node);
    }
    /**
     * Adjusts the remote rows and cost based on the limit, removes limit elements
     *
     * @param node
     * @return
     * @throws StandardException
     */

    @Override
    public Visitable visit(HalfOuterJoinNode node) throws StandardException {
        adjustCost(node);
        nullify();
        return super.visit(node);
    }
    /**
     * Adjusts the remote rows and cost based on the limit, removes limit elements
     *
     * @param node
     * @return
     * @throws StandardException
     */

    @Override
    public Visitable visit(RowResultSetNode node) throws StandardException {
        adjustCost(node);
        nullify();
        return super.visit(node);
    }
    /**
     * Adjusts the remote rows and cost based on the limit, removes limit elements
     *
     * @param node
     * @return
     * @throws StandardException
     */

    @Override
    public Visitable visit(IntersectOrExceptNode node) throws StandardException {
        adjustCost(node);
        nullify();
        return super.visit(node);
    }

    @Override
    public Visitable visit(MaterializeResultSetNode node) throws StandardException {
        nullify();
        return super.visit(node);
    }

    /**
     * Adjusts the remote rows and cost based on the limit, removes limit elements
     *
     * @param node
     * @return
     * @throws StandardException
     */

    @Override
    public Visitable visit(UnionNode node) throws StandardException {
        adjustCost(node);
        nullify();
        return super.visit(node);
    }

    @Override
    public Visitable visit(WindowResultSetNode node) throws StandardException {
        nullify();
        return super.visit(node);
    }
    /**
     * Adjusts the remote rows and cost based on the limit, removes limit elements
     *
     * @param node
     * @return
     * @throws StandardException
     */

    @Override
    public Visitable visit(OrderByNode node) throws StandardException {
        adjustCost(node);
        nullify();
        return super.visit(node);
    }

    @Override
    public Visitable visit(ExportNode node) throws StandardException {
        nullify();
        return super.visit(node);
    }

    @Override
    public Visitable visit(SubqueryNode node) throws StandardException {
        nullify();
        return super.visit(node);
    }

    /**
     * {@inheritDoc}
     * @return {@code false}, since the tree should be walked top down
     */
    public boolean visitChildrenFirst(Visitable node) {
        return false;
    }

    /**
     * Top Down ordering to see how far to push limits
     */
    public boolean isPostOrder() {
        return false;
    }

    /**
     * Remove the limit items
     *
     */
    private void nullify() {
        offset = -1;
        fetchFirst = -1;
    }

    /**
     *
     * Adjust normal node costing, row count and remote cost reduction
     *
     * Need to account for offset
     *
     * @param rsn
     * @throws StandardException
     */

    public void adjustCost(ResultSetNode rsn) throws StandardException {
        if (fetchFirst==-1 && offset ==-1) // No Limit Adjustment
            return;
        CostEstimate costEstimate = rsn.getFinalCostEstimate();
        long totalRowCount = costEstimate.getEstimatedRowCount();
        long currentOffset = offset==-1?0:offset;
        long currentFetchFirst = fetchFirst==-1?totalRowCount:fetchFirst;
        scaleFactor = (double) currentFetchFirst/(double) totalRowCount;
        if (scaleFactor >= 1.0d) {
            // do nothing, will not effect cost
        } else {
            costEstimate.setEstimatedRowCount(currentOffset+currentFetchFirst);
            costEstimate.setRemoteCost(scaleFactor*costEstimate.getRemoteCost());
        }

    }

    /**
     *
     * Adjust base table costing...  Attacks BaseTable, Projections, and Index Lookups, etc.
     *
     * Need to account for offset better.
     *
     * @param rsn
     * @throws StandardException
     */
    public void adjustBaseTableCost(ResultSetNode rsn) throws StandardException {
        if (fetchFirst==-1 && offset ==-1) // No Limit Adjustment
            return;
        CostEstimate costEstimate = rsn.getFinalCostEstimate();
        long totalRowCount = costEstimate.getEstimatedRowCount();
        long currentOffset = offset==-1?0:offset;
        long currentFetchFirst = fetchFirst==-1?totalRowCount:fetchFirst;
        scaleFactor = (double) currentFetchFirst/(double) totalRowCount;
        if (scaleFactor >= 1.0d) {
                // do nothing, will not effect cost
        } else {
                costEstimate.setEstimatedRowCount(currentFetchFirst);
                costEstimate.setSingleScanRowCount(currentFetchFirst);
                costEstimate.setRowCount(currentFetchFirst);
                costEstimate.setProjectionRows(currentFetchFirst);
                costEstimate.setIndexLookupRows(costEstimate.getIndexLookupRows()*scaleFactor);
                costEstimate.setEstimatedHeapSize((long)(costEstimate.getEstimatedHeapSize()*scaleFactor));
                costEstimate.setRemoteCost((long)(costEstimate.getRemoteCost()*scaleFactor));
                costEstimate.setFromBaseTableCost(costEstimate.getFromBaseTableCost()*scaleFactor);
                costEstimate.setFromBaseTableRows(costEstimate.getFromBaseTableRows()*scaleFactor);
                costEstimate.setEstimatedCost(costEstimate.getEstimatedCost()*scaleFactor);
        }
    }


/*

<!-- This was the original code in the RowCountNode -->

    public void fixCost() throws StandardException {
        if (fetchFirst != null && fetchFirst instanceof NumericConstantNode) {
            long totalRowCount = costEstimate.getEstimatedRowCount();
            long fetchCount = ((NumericConstantNode)fetchFirst).getValue().getInt();
            double factor = (double)fetchCount/(double)totalRowCount;
            costEstimate.setEstimatedRowCount(fetchCount);
            costEstimate.setSingleScanRowCount(fetchCount);
            costEstimate.setEstimatedHeapSize((long)(costEstimate.getEstimatedHeapSize()*factor));
            costEstimate.setRemoteCost((long)(costEstimate.getRemoteCost()*factor));
        }
        else
        if (offset != null && offset instanceof NumericConstantNode) {
            long totalRowCount = costEstimate.getEstimatedRowCount();
            long offsetCount = ((NumericConstantNode)offset).getValue().getInt();
            costEstimate.setEstimatedRowCount(totalRowCount-offsetCount >=1? totalRowCount-offsetCount:1); // Snap to 1
        } else {
            // Nothing
        }
    }

*/
}