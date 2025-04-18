/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.planner.optimizations;

import com.facebook.presto.Session;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.spi.VariableAllocator;
import com.facebook.presto.spi.WarningCollector;
import com.facebook.presto.spi.plan.EquiJoinClause;
import com.facebook.presto.spi.plan.JoinNode;
import com.facebook.presto.spi.plan.MergeJoinNode;
import com.facebook.presto.spi.plan.PlanNode;
import com.facebook.presto.spi.plan.PlanNodeIdAllocator;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.sql.planner.TypeProvider;
import com.facebook.presto.sql.planner.plan.SimplePlanRewriter;

import java.util.List;

import static com.facebook.presto.SystemSessionProperties.isGroupedExecutionEnabled;
import static com.facebook.presto.SystemSessionProperties.isSingleNodeExecutionEnabled;
import static com.facebook.presto.SystemSessionProperties.preferMergeJoinForSortedInputs;
import static com.facebook.presto.common.block.SortOrder.ASC_NULLS_FIRST;
import static com.facebook.presto.spi.plan.JoinType.INNER;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

public class MergeJoinForSortedInputOptimizer
        implements PlanOptimizer
{
    private final Metadata metadata;
    private final boolean nativeExecution;
    private boolean isEnabledForTesting;

    public MergeJoinForSortedInputOptimizer(Metadata metadata, boolean nativeExecution)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.nativeExecution = nativeExecution;
    }

    @Override
    public void setEnabledForTesting(boolean isSet)
    {
        isEnabledForTesting = isSet;
    }

    @Override
    public boolean isEnabled(Session session)
    {
        return isEnabledForTesting || isGroupedExecutionEnabled(session) && preferMergeJoinForSortedInputs(session) && !isSingleNodeExecutionEnabled(session);
    }

    @Override
    public PlanOptimizerResult optimize(PlanNode plan, Session session, TypeProvider type, VariableAllocator variableAllocator, PlanNodeIdAllocator idAllocator, WarningCollector warningCollector)
    {
        requireNonNull(plan, "plan is null");
        requireNonNull(session, "session is null");
        requireNonNull(variableAllocator, "variableAllocator is null");
        requireNonNull(idAllocator, "idAllocator is null");

        if (isEnabled(session)) {
            Rewriter rewriter = new MergeJoinForSortedInputOptimizer.Rewriter(variableAllocator, idAllocator, metadata, session);
            PlanNode rewrittenPlan = SimplePlanRewriter.rewriteWith(rewriter, plan, null);
            return PlanOptimizerResult.optimizerResult(rewrittenPlan, rewriter.isPlanChanged());
        }
        return PlanOptimizerResult.optimizerResult(plan, false);
    }

    private class Rewriter
            extends SimplePlanRewriter<Void>
    {
        private final PlanNodeIdAllocator idAllocator;
        private final Metadata metadata;
        private final Session session;
        private final TypeProvider types;
        private boolean planChanged;

        private Rewriter(VariableAllocator variableAllocator, PlanNodeIdAllocator idAllocator, Metadata metadata, Session session)
        {
            this.idAllocator = requireNonNull(idAllocator, "idAllocator is null");
            this.metadata = requireNonNull(metadata, "metadata is null");
            this.session = requireNonNull(session, "session is null");
            this.types = TypeProvider.viewOf(variableAllocator.getVariables());
        }

        public boolean isPlanChanged()
        {
            return planChanged;
        }

        @Override
        public PlanNode visitJoin(JoinNode node, RewriteContext<Void> context)
        {
            // As of now, we only support inner join for merge join
            if (node.getType() != INNER) {
                return node;
            }

            // Fast path merge join optimization (no sort, no local merge)

            // For example: when we have a plan that looks like:
            // JoinNode
            //- TableScanA
            //- TableScanB

            // We check the data properties of TableScanA and TableScanB to see if they meet requirements for merge join:
            // 1. If so, we replace the JoinNode to MergeJoinNode
            // MergeJoinNode
            //- TableScanA
            //- TableScanB

            // 2. If not, we don't optimize
            if (meetsDataRequirement(node.getLeft(), node.getRight(), node)) {
                planChanged = true;
                return new MergeJoinNode(
                        node.getSourceLocation(),
                        node.getId(),
                        node.getType(),
                        node.getLeft(),
                        node.getRight(),
                        node.getCriteria(),
                        node.getOutputVariables(),
                        node.getFilter(),
                        node.getLeftHashVariable(),
                        node.getRightHashVariable());
            }
            return node;
        }

        private boolean meetsDataRequirement(PlanNode left, PlanNode right, JoinNode node)
        {
            // Acquire data properties for both left and right side
            StreamPropertyDerivations.StreamProperties leftProperties = StreamPropertyDerivations.derivePropertiesRecursively(left, metadata, session, nativeExecution);
            StreamPropertyDerivations.StreamProperties rightProperties = StreamPropertyDerivations.derivePropertiesRecursively(right, metadata, session, nativeExecution);

            List<VariableReferenceExpression> leftJoinColumns = node.getCriteria().stream().map(EquiJoinClause::getLeft).collect(toImmutableList());
            List<VariableReferenceExpression> rightJoinColumns = node.getCriteria().stream()
                    .map(EquiJoinClause::getRight)
                    .collect(toImmutableList());

            // Check if both the left side and right side's partitioning columns (bucketed-by columns [B]) are a subset of join columns [J]
            // B = subset (J)
            if (!verifyStreamProperties(leftProperties, leftJoinColumns) || !verifyStreamProperties(rightProperties, rightJoinColumns)) {
                return false;
            }

            // Check if the left side and right side are both ordered by the join columns
            return !LocalProperties.match(rightProperties.getLocalProperties(), LocalProperties.sorted(rightJoinColumns, ASC_NULLS_FIRST)).get(0).isPresent() &&
                    !LocalProperties.match(leftProperties.getLocalProperties(), LocalProperties.sorted(leftJoinColumns, ASC_NULLS_FIRST)).get(0).isPresent();
        }

        private boolean verifyStreamProperties(StreamPropertyDerivations.StreamProperties streamProperties, List<VariableReferenceExpression> joinColumns)
        {
            if (!streamProperties.getPartitioningColumns().isPresent()) {
                return false;
            }
            List<VariableReferenceExpression> partitioningColumns = streamProperties.getPartitioningColumns().get();
            return partitioningColumns.size() <= joinColumns.size() && joinColumns.containsAll(partitioningColumns);
        }
    }
}
