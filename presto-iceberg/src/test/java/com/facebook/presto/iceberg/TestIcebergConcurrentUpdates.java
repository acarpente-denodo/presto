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

import com.facebook.presto.Session;
import com.facebook.presto.testing.QueryRunner;
import com.facebook.presto.tests.AbstractTestQueryFramework;
import com.facebook.presto.tests.DistributedQueryRunner;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static com.facebook.presto.SystemSessionProperties.QUERY_RETRY_LIMIT;
import static java.lang.String.format;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * When two statements update the same Iceberg table at the same time, the loser of the race has its
 * commit rejected with an Iceberg {@code ValidationException}. Nothing was written, so the engine is
 * expected to run the statement again against the new state of the table rather than fail it.
 */
public class TestIcebergConcurrentUpdates
        extends AbstractTestQueryFramework
{
    private static final int WRITERS = 4;
    private static final int ROUNDS = 5;

    private ExecutorService executor;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        return IcebergQueryRunner.builder()
                .setCreateTpchTables(false)
                .build()
                .getQueryRunner();
    }

    @BeforeClass(alwaysRun = true)
    public void setUp()
    {
        executor = newFixedThreadPool(WRITERS);
    }

    @AfterClass(alwaysRun = true)
    public void tearDown()
    {
        executor.shutdownNow();
        executor = null;
    }

    @Test
    public void testConcurrentUpdatesAreRetried()
            throws Exception
    {
        String tableName = "test_concurrent_updates";
        assertUpdate("DROP TABLE IF EXISTS " + tableName);
        assertUpdate(format("CREATE TABLE %s (id integer, value integer)", tableName));
        assertUpdate(format("INSERT INTO %s SELECT CAST(id AS integer), 0 FROM UNNEST(sequence(1, %s)) AS t(id)", tableName, WRITERS), WRITERS);

        Session session = Session.builder(getSession())
                .setSystemProperty(QUERY_RETRY_LIMIT, String.valueOf(WRITERS * ROUNDS))
                .build();

        for (int round = 0; round < ROUNDS; round++) {
            // each writer only touches its own row, so the statements only conflict when committing
            CyclicBarrier startTogether = new CyclicBarrier(WRITERS);
            List<Future<?>> updates = new ArrayList<>();
            for (int writer = 1; writer <= WRITERS; writer++) {
                String sql = format("UPDATE %s SET value = value + 1 WHERE id = %s", tableName, writer);
                updates.add(executor.submit(() -> {
                    startTogether.await(30, SECONDS);
                    return getQueryRunner().execute(session, sql);
                }));
            }

            for (Future<?> update : updates) {
                // throws ICEBERG_COMMIT_CONFLICT if a rejected commit is not retried
                update.get();
            }
        }

        assertEquals(computeScalar(format("SELECT count(*) FROM %s WHERE value = %s", tableName, ROUNDS)), (long) WRITERS);
        // the whole point of the test: the statements above did lose commit races and were run again
        assertTrue(countRetriedQueries() > 0, "expected concurrent updates to be retried");

        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    public void testConflictingUpdateIsReportedAsRetriableError()
    {
        String tableName = "test_update_commit_conflict";
        assertUpdate("DROP TABLE IF EXISTS " + tableName);
        assertUpdate(format("CREATE TABLE %s (id integer, value integer)", tableName));
        assertUpdate(format("INSERT INTO %s VALUES (1, 10), (2, 20)", tableName), 2);

        // an explicit transaction is never retried, so the conflict is surfaced to the client
        Session session = getSession();
        Session transactionSession = assertStartTransaction(session, "START TRANSACTION");
        assertUpdate(transactionSession, format("UPDATE %s SET value = value + 1 WHERE id = 1", tableName), 1);

        // commits while the transaction above still holds an uncommitted row delta for the same table
        assertUpdate(session, format("UPDATE %s SET value = value + 100 WHERE id = 2", tableName), 1);

        assertQueryFails(transactionSession, "COMMIT", ".*was concurrently modified.*");

        assertQuery(session, "SELECT id, value FROM " + tableName, "VALUES (1, 10), (2, 120)");
        assertUpdate(session, "DROP TABLE " + tableName);
    }

    private long countRetriedQueries()
    {
        return ((DistributedQueryRunner) getQueryRunner()).getCoordinator().getQueryManager().getQueries().stream()
                .filter(query -> query.getQuery().contains("-- retry query"))
                .count();
    }
}
