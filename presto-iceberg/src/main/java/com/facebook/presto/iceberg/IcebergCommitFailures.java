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
package com.facebook.presto.iceberg;

import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.SchemaTableName;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.exceptions.ValidationException;

import java.util.function.BooleanSupplier;

import static com.facebook.presto.iceberg.IcebergErrorCode.ICEBERG_COMMIT_CONFLICT;
import static com.facebook.presto.iceberg.IcebergErrorCode.ICEBERG_COMMIT_ERROR;
import static java.lang.String.format;

public final class IcebergCommitFailures
{
    private IcebergCommitFailures() {}

    /**
     * Translates a failure raised while committing an Iceberg transaction into a {@link PrestoException}.
     * <p>
     * Commits that were rejected because another writer changed the table concurrently are reported with
     * the retriable {@link IcebergErrorCode#ICEBERG_COMMIT_CONFLICT}, so that the engine can execute the
     * statement again against the new state of the table. Everything else keeps the non-retriable
     * {@link IcebergErrorCode#ICEBERG_COMMIT_ERROR}.
     *
     * @param tableChangedConcurrently tells whether the table moved to a snapshot written by somebody else
     * while this transaction was open. It is only consulted for the exception types that a conflict can be
     * reported with, so callers may implement it with a metadata refresh.
     */
    public static PrestoException toPrestoException(RuntimeException failure, SchemaTableName tableName, BooleanSupplier tableChangedConcurrently)
    {
        if (isCommitConflict(failure, tableChangedConcurrently)) {
            return new PrestoException(
                    ICEBERG_COMMIT_CONFLICT,
                    format("Failed to commit Iceberg update to table %s because it was concurrently modified", tableName),
                    failure);
        }
        return new PrestoException(ICEBERG_COMMIT_ERROR, "Failed to commit Iceberg update to table: " + tableName, failure);
    }

    private static boolean isCommitConflict(RuntimeException failure, BooleanSupplier tableChangedConcurrently)
    {
        // The commit may or may not have been applied. Executing the statement again could apply it twice.
        if (failure instanceof CommitStateUnknownException) {
            return false;
        }

        // A CommitFailedException means Iceberg exhausted its own commit retries, and a ValidationException
        // means one of the conflict validators of the operation rejected the new table state. In both cases
        // nothing was written. Iceberg only inspects the snapshots added after the snapshot the operation
        // read from, so a genuine conflict always comes with a concurrent change; requiring one here keeps
        // the deterministic validation failures (an invalid schema, an unusable metastore object, ...) out
        // of the retriable bucket.
        if (failure instanceof CommitFailedException || failure instanceof ValidationException) {
            return tableChangedConcurrently.getAsBoolean();
        }

        return false;
    }
}
