/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.server.storage;

import java.util.List;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;
import com.google.common.collect.Lists;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.common.model.QueryCollector;
import org.glowroot.storage.repo.AggregateRepository.ErrorSummarySortOrder;
import org.glowroot.storage.repo.AggregateRepository.OverallErrorSummary;
import org.glowroot.storage.repo.AggregateRepository.OverallQuery;
import org.glowroot.storage.repo.AggregateRepository.OverallSummary;
import org.glowroot.storage.repo.AggregateRepository.OverviewAggregate;
import org.glowroot.storage.repo.AggregateRepository.PercentileAggregate;
import org.glowroot.storage.repo.AggregateRepository.SummarySortOrder;
import org.glowroot.storage.repo.AggregateRepository.ThroughputAggregate;
import org.glowroot.storage.repo.AggregateRepository.TransactionErrorSummary;
import org.glowroot.storage.repo.AggregateRepository.TransactionQuery;
import org.glowroot.storage.repo.AggregateRepository.TransactionSummary;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ImmutableOverallQuery;
import org.glowroot.storage.repo.ImmutableTransactionQuery;
import org.glowroot.storage.repo.Result;
import org.glowroot.storage.repo.TransactionErrorSummaryCollector;
import org.glowroot.storage.repo.TransactionSummaryCollector;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate.QueriesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate.Query;
import org.glowroot.wire.api.model.AggregateOuterClass.AggregatesByType;
import org.glowroot.wire.api.model.AggregateOuterClass.TransactionAggregate;
import org.glowroot.wire.api.model.Proto.OptionalInt64;

import static org.assertj.core.api.Assertions.assertThat;

public class AggregateDaoIT {

    private static Session session;
    private static AggregateDao aggregateDao;

    @BeforeClass
    public static void setUp() throws Exception {
        CassandraWrapper.start();
        Cluster cluster = Cluster.builder().addContactPoint("127.0.0.1").build();
        session = cluster.newSession();
        session.execute("create keyspace if not exists glowroot with replication ="
                + " { 'class' : 'SimpleStrategy', 'replication_factor' : 1 }");
        session.execute("use glowroot");

        AgentDao agentDao = new AgentDao(session);
        ServerConfigDao serverConfigDao = new ServerConfigDao(session);
        ConfigRepository configRepository = new ConfigRepositoryImpl(serverConfigDao, agentDao);
        agentDao.setConfigRepository(configRepository);
        serverConfigDao.setConfigRepository(configRepository);
        TransactionTypeDao transactionTypeDao = new TransactionTypeDao(session, configRepository);
        aggregateDao = new AggregateDao(session, agentDao, transactionTypeDao, configRepository);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        session.close();
        CassandraWrapper.stop();
    }

    @Test
    public void shouldRollup() throws Exception {
        aggregateDao.truncateAll();
        aggregateDao.store("one", 60000, createData());
        aggregateDao.store("one", 120000, createData());

        // check non-rolled up data
        OverallQuery overallQuery = ImmutableOverallQuery.builder()
                .agentRollup("one")
                .transactionType("tt1")
                .from(0)
                .to(300000)
                .rollupLevel(0)
                .build();
        TransactionQuery transactionQuery = ImmutableTransactionQuery.builder()
                .agentRollup("one")
                .transactionType("tt1")
                .from(0)
                .to(300000)
                .rollupLevel(0)
                .build();

        OverallSummary overallSummary = aggregateDao.readOverallSummary(overallQuery);
        assertThat(overallSummary.totalDurationNanos()).isEqualTo(3579 * 2);
        assertThat(overallSummary.transactionCount()).isEqualTo(6);

        TransactionSummaryCollector summaryCollector = new TransactionSummaryCollector();
        SummarySortOrder sortOrder = SummarySortOrder.TOTAL_TIME;
        aggregateDao.mergeInTransactionSummaries(summaryCollector, overallQuery,
                sortOrder, 10);
        Result<TransactionSummary> result = summaryCollector.getResult(sortOrder, 10);
        assertThat(result.records()).hasSize(2);
        assertThat(result.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(result.records().get(0).totalDurationNanos()).isEqualTo(2345 * 2);
        assertThat(result.records().get(0).transactionCount()).isEqualTo(4);
        assertThat(result.records().get(1).transactionName()).isEqualTo("tn1");
        assertThat(result.records().get(1).totalDurationNanos()).isEqualTo(1234 * 2);
        assertThat(result.records().get(1).transactionCount()).isEqualTo(2);

        OverallErrorSummary overallErrorSummary =
                aggregateDao.readOverallErrorSummary(overallQuery);
        assertThat(overallErrorSummary.errorCount()).isEqualTo(2);
        assertThat(overallErrorSummary.transactionCount()).isEqualTo(6);

        TransactionErrorSummaryCollector errorSummaryCollector =
                new TransactionErrorSummaryCollector();
        ErrorSummarySortOrder errorSortOrder = ErrorSummarySortOrder.ERROR_COUNT;
        aggregateDao.mergeInTransactionErrorSummaries(errorSummaryCollector, overallQuery,
                errorSortOrder, 10);
        Result<TransactionErrorSummary> errorSummaryResult =
                errorSummaryCollector.getResult(errorSortOrder, 10);
        assertThat(errorSummaryResult.records()).hasSize(1);
        assertThat(errorSummaryResult.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(errorSummaryResult.records().get(0).errorCount()).isEqualTo(2);
        assertThat(errorSummaryResult.records().get(0).transactionCount()).isEqualTo(4);

        List<OverviewAggregate> overviewAggregates =
                aggregateDao.readOverviewAggregates(transactionQuery);
        assertThat(overviewAggregates).hasSize(2);
        assertThat(overviewAggregates.get(0).transactionCount()).isEqualTo(3);
        assertThat(overviewAggregates.get(1).transactionCount()).isEqualTo(3);

        List<PercentileAggregate> percentileAggregates =
                aggregateDao.readPercentileAggregates(transactionQuery);
        assertThat(percentileAggregates).hasSize(2);
        assertThat(percentileAggregates.get(0).transactionCount()).isEqualTo(3);
        assertThat(percentileAggregates.get(1).transactionCount()).isEqualTo(3);

        List<ThroughputAggregate> throughputAggregates =
                aggregateDao.readThroughputAggregates(transactionQuery);
        assertThat(throughputAggregates).hasSize(2);
        assertThat(throughputAggregates.get(0).transactionCount()).isEqualTo(3);
        assertThat(throughputAggregates.get(1).transactionCount()).isEqualTo(3);

        QueryCollector queryCollector = new QueryCollector(1000, 0);
        aggregateDao.mergeInQueries(queryCollector, transactionQuery);
        List<QueriesByType> queriesByType = queryCollector.toProto();
        assertThat(queriesByType).hasSize(1);
        assertThat(queriesByType.get(0).getType()).isEqualTo("sqlo");
        assertThat(queriesByType.get(0).getQueryCount()).isEqualTo(1);
        assertThat(queriesByType.get(0).getQuery(0).getText()).isEqualTo("select 1");
        assertThat(queriesByType.get(0).getQuery(0).getTotalDurationNanos()).isEqualTo(14);
        assertThat(queriesByType.get(0).getQuery(0).getTotalRows().getValue()).isEqualTo(10);
        assertThat(queriesByType.get(0).getQuery(0).getExecutionCount()).isEqualTo(4);

        // check that rolled-up data does not exist before rollup
        overallQuery = ImmutableOverallQuery.builder()
                .copyFrom(overallQuery)
                .rollupLevel(1)
                .build();
        transactionQuery = ImmutableTransactionQuery.builder()
                .copyFrom(transactionQuery)
                .rollupLevel(1)
                .build();
        overallSummary = aggregateDao.readOverallSummary(overallQuery);
        assertThat(overallSummary.totalDurationNanos()).isEqualTo(0);
        assertThat(overallSummary.transactionCount()).isEqualTo(0);

        summaryCollector = new TransactionSummaryCollector();
        aggregateDao.mergeInTransactionSummaries(summaryCollector, overallQuery,
                sortOrder, 10);
        result = summaryCollector.getResult(sortOrder, 10);
        assertThat(result.records()).isEmpty();

        overallErrorSummary = aggregateDao.readOverallErrorSummary(overallQuery);
        assertThat(overallErrorSummary.errorCount()).isEqualTo(0);
        assertThat(overallErrorSummary.transactionCount()).isEqualTo(0);

        errorSummaryCollector = new TransactionErrorSummaryCollector();
        aggregateDao.mergeInTransactionErrorSummaries(errorSummaryCollector, overallQuery,
                errorSortOrder, 10);
        errorSummaryResult = errorSummaryCollector.getResult(errorSortOrder, 10);
        assertThat(errorSummaryResult.records()).isEmpty();

        overviewAggregates = aggregateDao.readOverviewAggregates(transactionQuery);
        assertThat(overviewAggregates).isEmpty();

        percentileAggregates = aggregateDao.readPercentileAggregates(transactionQuery);
        assertThat(percentileAggregates).isEmpty();

        throughputAggregates = aggregateDao.readThroughputAggregates(transactionQuery);
        assertThat(throughputAggregates).isEmpty();

        queryCollector = new QueryCollector(1000, 0);
        aggregateDao.mergeInQueries(queryCollector, transactionQuery);
        queriesByType = queryCollector.toProto();
        assertThat(queriesByType).isEmpty();

        // rollup
        aggregateDao.rollup(300001);
        aggregateDao.rollup(300001);
        aggregateDao.rollup(300001);
        aggregateDao.rollup(300001);

        // check rolled-up data after rollup
        overallSummary = aggregateDao.readOverallSummary(overallQuery);
        assertThat(overallSummary.totalDurationNanos()).isEqualTo(3579 * 2);
        assertThat(overallSummary.transactionCount()).isEqualTo(6);

        summaryCollector = new TransactionSummaryCollector();
        aggregateDao.mergeInTransactionSummaries(summaryCollector, overallQuery,
                sortOrder, 10);
        result = summaryCollector.getResult(sortOrder, 10);
        assertThat(result.records()).hasSize(2);
        assertThat(result.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(result.records().get(0).totalDurationNanos()).isEqualTo(2345 * 2);
        assertThat(result.records().get(0).transactionCount()).isEqualTo(4);
        assertThat(result.records().get(1).transactionName()).isEqualTo("tn1");
        assertThat(result.records().get(1).totalDurationNanos()).isEqualTo(1234 * 2);
        assertThat(result.records().get(1).transactionCount()).isEqualTo(2);

        overallErrorSummary = aggregateDao.readOverallErrorSummary(overallQuery);
        assertThat(overallErrorSummary.errorCount()).isEqualTo(2);
        assertThat(overallErrorSummary.transactionCount()).isEqualTo(6);

        errorSummaryCollector = new TransactionErrorSummaryCollector();
        aggregateDao.mergeInTransactionErrorSummaries(errorSummaryCollector, overallQuery,
                errorSortOrder, 10);
        errorSummaryResult = errorSummaryCollector.getResult(errorSortOrder, 10);
        assertThat(errorSummaryResult.records()).hasSize(1);
        assertThat(errorSummaryResult.records().get(0).transactionName()).isEqualTo("tn2");
        assertThat(errorSummaryResult.records().get(0).errorCount()).isEqualTo(2);
        assertThat(errorSummaryResult.records().get(0).transactionCount()).isEqualTo(4);

        overviewAggregates = aggregateDao.readOverviewAggregates(transactionQuery);
        assertThat(overviewAggregates).hasSize(1);
        assertThat(overviewAggregates.get(0).transactionCount()).isEqualTo(6);

        percentileAggregates = aggregateDao.readPercentileAggregates(transactionQuery);
        assertThat(percentileAggregates).hasSize(1);
        assertThat(percentileAggregates.get(0).transactionCount()).isEqualTo(6);

        throughputAggregates = aggregateDao.readThroughputAggregates(transactionQuery);
        assertThat(throughputAggregates).hasSize(1);
        assertThat(throughputAggregates.get(0).transactionCount()).isEqualTo(6);

        queryCollector = new QueryCollector(1000, 0);
        aggregateDao.mergeInQueries(queryCollector, transactionQuery);
        queriesByType = queryCollector.toProto();
        assertThat(queriesByType).hasSize(1);
        assertThat(queriesByType.get(0).getType()).isEqualTo("sqlo");
        assertThat(queriesByType.get(0).getQueryCount()).isEqualTo(1);
        assertThat(queriesByType.get(0).getQuery(0).getText()).isEqualTo("select 1");
        assertThat(queriesByType.get(0).getQuery(0).getTotalDurationNanos()).isEqualTo(14);
        assertThat(queriesByType.get(0).getQuery(0).getTotalRows().getValue()).isEqualTo(10);
        assertThat(queriesByType.get(0).getQuery(0).getExecutionCount()).isEqualTo(4);
    }

    private static List<AggregatesByType> createData() {
        List<AggregatesByType> aggregatesByType = Lists.newArrayList();
        aggregatesByType.add(AggregatesByType.newBuilder()
                .setTransactionType("tt0")
                .setOverallAggregate(createOverallAggregate())
                .addTransactionAggregate(createTransactionAggregate1())
                .addTransactionAggregate(createTransactionAggregate2())
                .build());
        aggregatesByType.add(AggregatesByType.newBuilder()
                .setTransactionType("tt1")
                .setOverallAggregate(createOverallAggregate())
                .addTransactionAggregate(createTransactionAggregate1())
                .addTransactionAggregate(createTransactionAggregate2())
                .build());
        return aggregatesByType;
    }

    private static Aggregate createOverallAggregate() {
        return Aggregate.newBuilder()
                .setTotalDurationNanos(3579)
                .setTransactionCount(3)
                .setErrorCount(1)
                .addMainThreadRootTimer(Aggregate.Timer.newBuilder()
                        .setName("abc")
                        .setTotalNanos(333)
                        .setCount(3))
                .addAuxThreadRootTimer(Aggregate.Timer.newBuilder()
                        .setName("xyz")
                        .setTotalNanos(666)
                        .setCount(3))
                .addAsyncRootTimer(Aggregate.Timer.newBuilder()
                        .setName("mnm")
                        .setTotalNanos(999)
                        .setCount(3))
                .addQueriesByType(QueriesByType.newBuilder()
                        .setType("sqlo")
                        .addQuery(Query.newBuilder()
                                .setText("select 1")
                                .setTotalDurationNanos(7)
                                .setTotalRows(OptionalInt64.newBuilder().setValue(5))
                                .setExecutionCount(2)))
                .build();
    }

    private static TransactionAggregate createTransactionAggregate1() {
        return TransactionAggregate.newBuilder()
                .setTransactionName("tn1")
                .setAggregate(Aggregate.newBuilder()
                        .setTotalDurationNanos(1234)
                        .setTransactionCount(1)
                        .addMainThreadRootTimer(Aggregate.Timer.newBuilder()
                                .setName("abc")
                                .setTotalNanos(111)
                                .setCount(1))
                        .addAuxThreadRootTimer(Aggregate.Timer.newBuilder()
                                .setName("xyz")
                                .setTotalNanos(222)
                                .setCount(1))
                        .addAsyncRootTimer(Aggregate.Timer.newBuilder()
                                .setName("mnm")
                                .setTotalNanos(333)
                                .setCount(1))
                        .addQueriesByType(QueriesByType.newBuilder()
                                .setType("sqlo")
                                .addQuery(Query.newBuilder()
                                        .setText("select 1")
                                        .setTotalDurationNanos(7)
                                        .setTotalRows(OptionalInt64.newBuilder().setValue(5))
                                        .setExecutionCount(2))))
                .build();
    }

    private static TransactionAggregate createTransactionAggregate2() {
        return TransactionAggregate.newBuilder()
                .setTransactionName("tn2")
                .setAggregate(Aggregate.newBuilder()
                        .setTotalDurationNanos(2345)
                        .setTransactionCount(2)
                        .setErrorCount(1)
                        .addMainThreadRootTimer(Aggregate.Timer.newBuilder()
                                .setName("abc")
                                .setTotalNanos(222)
                                .setCount(2))
                        .addAuxThreadRootTimer(Aggregate.Timer.newBuilder()
                                .setName("xyz")
                                .setTotalNanos(444)
                                .setCount(2))
                        .addAsyncRootTimer(Aggregate.Timer.newBuilder()
                                .setName("mnm")
                                .setTotalNanos(666)
                                .setCount(2)))
                .build();
    }
}
