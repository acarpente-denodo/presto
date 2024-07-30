package com.facebook.presto.operator;

import com.facebook.airlift.json.JsonCodec;
import com.facebook.presto.common.Page;
import com.facebook.presto.spi.plan.PlanNodeId;
import com.facebook.presto.sql.planner.plan.TableWriterNode.MergeParadigmAndTypes;

import java.util.List;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

// TODO: EL CÓDIGO DE ESTÁ CLASE ESTÁ SIN IMPLEMENTAR.

/**
 * This operator is used by operations like SQL MERGE.  It is used
 * for all {@link com.facebook.presto.spi.connector.RowChangeParadigm}s.  This operator
 * creates the {@link MergeRowChangeProcessor}.
 */
public class MergeIntoOperator
        extends AbstractRowChangeOperator
{
    public static class MergeIntoOperatorFactory
            implements OperatorFactory
    {
        private final int operatorId;
        private final PlanNodeId planNodeId;
        private final MergeRowChangeProcessor rowChangeProcessor;
        private final List<Integer> columnValueAndRowIdChannels;
        private final JsonCodec<TableCommitContext> tableCommitContextCodec;
        private boolean closed;

        public MergeIntoOperatorFactory(int operatorId, PlanNodeId planNodeId, MergeRowChangeProcessor rowChangeProcessor,
                List<Integer> columnValueAndRowIdChannels, JsonCodec<TableCommitContext> tableCommitContextCodec)
        {
            this.operatorId = operatorId;
            this.planNodeId = requireNonNull(planNodeId, "planNodeId is null");
            this.rowChangeProcessor = requireNonNull(rowChangeProcessor, "rowChangeProcessor is null");
            this.columnValueAndRowIdChannels = requireNonNull(columnValueAndRowIdChannels, "columnValueAndRowIdChannels is null");
            this.tableCommitContextCodec = requireNonNull(tableCommitContextCodec, "tableCommitContextCodec is null");
        }

        @Override
        public Operator createOperator(DriverContext driverContext)
        {
            checkState(!closed, "Factory is already closed");
            OperatorContext context = driverContext.addOperatorContext(operatorId, planNodeId, MergeIntoOperator.class.getSimpleName());
            return new MergeIntoOperator(context, /*rowChangeProcessor,*/ columnValueAndRowIdChannels, tableCommitContextCodec);
        }

//        @Override
//        public SourceOperator createOperator(DriverContext driverContext/* TODO: parámetros de Trino. ProcessorContext processorContext, WorkProcessor<Page> sourcePages*/)
//        {
//            checkState(!closed, "Factory is already closed");
//            OperatorContext operatorContext = driverContext.addOperatorContext(operatorId, planNodeId, MergeIntoOperator.class.getSimpleName());
////            return new MergeIntoOperator(
////                    operatorContext,
////                    planNodeId,
////                    pageSourceProvider,
////                    cursorProcessor.get(),
////                    pageProcessor.get(),
////                    table,
////                    columns,
////                    types,
////                    dynamicFilterSupplier,
////                    new MergingPageOutput(types, minOutputPageSize.toBytes(), minOutputPageRowCount));
//            return null; // TODO: Cambiar este valor.
//        }

        @Override
        public void noMoreOperators()
        {
            closed = true;
        }

        @Override
        public MergeIntoOperatorFactory duplicate()
        {
            return new MergeIntoOperatorFactory(operatorId, planNodeId, rowChangeProcessor, columnValueAndRowIdChannels, tableCommitContextCodec);
        }
    }

    // TODO: evaluar si este método es necesario.
    public static OperatorFactory createOperatorFactory(
            int operatorId,
            PlanNodeId planNodeId,
            MergeParadigmAndTypes merge,
            int rowIdChannel,
            int mergeRowChannel,
            List<Integer> redistributionColumns,
            List<Integer> dataColumnChannels,
            List<Integer> columnValueAndRowIdChannels,
            JsonCodec<TableCommitContext> tableCommitContextCodec)
    {
        MergeRowChangeProcessor rowChangeProcessor = createRowChangeProcessor(
                merge, rowIdChannel, mergeRowChannel, redistributionColumns, dataColumnChannels);
        return new MergeIntoOperatorFactory(operatorId, planNodeId, rowChangeProcessor, columnValueAndRowIdChannels, tableCommitContextCodec);
    }

    private static MergeRowChangeProcessor createRowChangeProcessor(
            MergeParadigmAndTypes merge,
            int rowIdChannel,
            int mergeRowChannel,
            List<Integer> redistributionColumnChannels,
            List<Integer> dataColumnChannels)
    {
        switch (merge.getParadigm()) {
            case DELETE_ROW_AND_INSERT_ROW:
                return new DeleteAndInsertMergeProcessor(
                        merge.getColumnTypes(),
                        merge.getRowIdType(),
                        rowIdChannel,
                        mergeRowChannel,
                        redistributionColumnChannels,
                        dataColumnChannels);
            case CHANGE_ONLY_UPDATED_COLUMNS:
                return new ChangeOnlyUpdatedColumnsMergeProcessor(
                        rowIdChannel,
                        mergeRowChannel,
                        dataColumnChannels,
                        redistributionColumnChannels);
        }

        return null; // TODO: Cambiar este valor.
    }

    // TODO: Código original de Trino.
//    private final WorkProcessor<Page> pages;
//
//    private MergeIntoOperator(
//            WorkProcessor<Page> sourcePages,
//            MergeRowChangeProcessor rowChangeProcessor)
//    {
//        pages = sourcePages
//                .transform(page -> {
//                    if (page == null) {
//                        return finished();
//                    }
//                    // return ofResult(rowChangeProcessor.transformPage(page)); TODO: Codigo original de Trino
//                    return finished(); // TODO: Codigo provisional para que compile.
//                });
//    }

    private final List<Integer> columnValueAndRowIdChannels;

    // TODO: Código creado a partir del UpdateOperator de Presto
    public MergeIntoOperator(OperatorContext operatorContext, List<Integer> columnValueAndRowIdChannels,
            JsonCodec<TableCommitContext> tableCommitContextCodec)
    {
        super(operatorContext, tableCommitContextCodec);
        this.columnValueAndRowIdChannels = columnValueAndRowIdChannels;
    }

    // TODO: Código creado a partir del UpdateOperator de Presto
    @Override
    public void addInput(Page page)
    {
        requireNonNull(page, "page is null");
        checkState(state == State.RUNNING, "Operator is %s", state);

        // Call the UpdatablePageSource to update rows in the page supplied.
        pageSource().updateRows(page, columnValueAndRowIdChannels);
        rowCount += page.getPositionCount();
    }
}
