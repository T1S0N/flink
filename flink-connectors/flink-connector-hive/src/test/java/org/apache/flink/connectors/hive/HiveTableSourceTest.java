/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.connectors.hive;

import org.apache.flink.api.dag.Transformation;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableUtils;
import org.apache.flink.table.api.internal.TableEnvironmentImpl;
import org.apache.flink.table.catalog.hive.HiveCatalog;
import org.apache.flink.table.catalog.hive.HiveTestUtils;
import org.apache.flink.table.planner.delegation.PlannerBase;
import org.apache.flink.table.planner.plan.nodes.exec.ExecNode;
import org.apache.flink.table.planner.utils.TableTestUtil;
import org.apache.flink.types.Row;

import com.klarna.hiverunner.HiveShell;
import com.klarna.hiverunner.annotations.HiveSQL;
import org.apache.calcite.rel.RelNode;
import org.apache.hadoop.hive.conf.HiveConf;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.apache.flink.table.planner.utils.JavaScalaConversionUtil.toScala;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests {@link HiveTableSource}.
 */
@RunWith(FlinkStandaloneHiveRunner.class)
public class HiveTableSourceTest {

	@HiveSQL(files = {})
	private static HiveShell hiveShell;

	private static HiveCatalog hiveCatalog;
	private static HiveConf hiveConf;

	@BeforeClass
	public static void createCatalog() throws IOException {
		hiveConf = hiveShell.getHiveConf();
		hiveCatalog = HiveTestUtils.createHiveCatalog(hiveConf);
		hiveCatalog.open();
	}

	@AfterClass
	public static void closeCatalog() {
		if (null != hiveCatalog) {
			hiveCatalog.close();
		}
	}

	@Before
	public void setupSourceDatabaseAndData() {
		hiveShell.execute("CREATE DATABASE IF NOT EXISTS source_db");
	}

	@Test
	public void testReadNonPartitionedTable() throws Exception {
		final String catalogName = "hive";
		final String dbName = "source_db";
		final String tblName = "test";
		hiveShell.execute("CREATE TABLE source_db.test ( a INT, b INT, c STRING, d BIGINT, e DOUBLE)");
		HiveTestUtils.createTextTableInserter(hiveShell, dbName, tblName)
				.addRow(new Object[]{1, 1, "a", 1000L, 1.11})
				.addRow(new Object[]{2, 2, "b", 2000L, 2.22})
				.addRow(new Object[]{3, 3, "c", 3000L, 3.33})
				.addRow(new Object[]{4, 4, "d", 4000L, 4.44})
				.commit();

		TableEnvironment tEnv = HiveTestUtils.createTableEnv();
		tEnv.registerCatalog(catalogName, hiveCatalog);
		Table src = tEnv.sqlQuery("select * from hive.source_db.test");
		List<Row> rows = TableUtils.collectToList(src);

		Assert.assertEquals(4, rows.size());
		Assert.assertEquals("1,1,a,1000,1.11", rows.get(0).toString());
		Assert.assertEquals("2,2,b,2000,2.22", rows.get(1).toString());
		Assert.assertEquals("3,3,c,3000,3.33", rows.get(2).toString());
		Assert.assertEquals("4,4,d,4000,4.44", rows.get(3).toString());
	}

	@Test
	public void testReadComplexDataType() throws Exception {
		final String catalogName = "hive";
		final String dbName = "source_db";
		final String tblName = "complex_test";
		hiveShell.execute("create table source_db.complex_test(" +
						"a array<int>, m map<int,string>, s struct<f1:int,f2:bigint>)");
		Integer[] array = new Integer[]{1, 2, 3};
		Map<Integer, String> map = new LinkedHashMap<>();
		map.put(1, "a");
		map.put(2, "b");
		Object[] struct = new Object[]{3, 3L};
		HiveTestUtils.createTextTableInserter(hiveShell, dbName, tblName)
				.addRow(new Object[]{array, map, struct})
				.commit();
		TableEnvironment tEnv = HiveTestUtils.createTableEnv();
		tEnv.registerCatalog(catalogName, hiveCatalog);
		Table src = tEnv.sqlQuery("select * from hive.source_db.complex_test");
		List<Row> rows = TableUtils.collectToList(src);
		Assert.assertEquals(1, rows.size());
		assertArrayEquals(array, (Integer[]) rows.get(0).getField(0));
		assertEquals(map, rows.get(0).getField(1));
		assertEquals(Row.of(struct[0], struct[1]), rows.get(0).getField(2));
	}

	/**
	 * Test to read from partition table.
	 * @throws Exception
	 */
	@Test
	public void testReadPartitionTable() throws Exception {
		final String catalogName = "hive";
		final String dbName = "source_db";
		final String tblName = "test_table_pt";
		hiveShell.execute("CREATE TABLE source_db.test_table_pt " +
						"(year STRING, value INT) partitioned by (pt int);");
		HiveTestUtils.createTextTableInserter(hiveShell, dbName, tblName)
				.addRow(new Object[]{"2014", 3})
				.addRow(new Object[]{"2014", 4})
				.commit("pt=0");
		HiveTestUtils.createTextTableInserter(hiveShell, dbName, tblName)
				.addRow(new Object[]{"2015", 2})
				.addRow(new Object[]{"2015", 5})
				.commit("pt=1");
		TableEnvironment tEnv = HiveTestUtils.createTableEnv();
		tEnv.registerCatalog(catalogName, hiveCatalog);
		Table src = tEnv.sqlQuery("select * from hive.source_db.test_table_pt");
		List<Row> rows = TableUtils.collectToList(src);

		assertEquals(4, rows.size());
		Object[] rowStrings = rows.stream().map(Row::toString).sorted().toArray();
		assertArrayEquals(new String[]{"2014,3,0", "2014,4,0", "2015,2,1", "2015,5,1"}, rowStrings);
	}

	@Test
	public void testPartitionPrunning() throws Exception {
		final String catalogName = "hive";
		final String dbName = "source_db";
		final String tblName = "test_table_pt_1";
		hiveShell.execute("CREATE TABLE source_db.test_table_pt_1 " +
						"(year STRING, value INT) partitioned by (pt int);");
		HiveTestUtils.createTextTableInserter(hiveShell, dbName, tblName)
				.addRow(new Object[]{"2014", 3})
				.addRow(new Object[]{"2014", 4})
				.commit("pt=0");
		HiveTestUtils.createTextTableInserter(hiveShell, dbName, tblName)
				.addRow(new Object[]{"2015", 2})
				.addRow(new Object[]{"2015", 5})
				.commit("pt=1");
		TableEnvironment tEnv = HiveTestUtils.createTableEnv();
		tEnv.registerCatalog(catalogName, hiveCatalog);
		Table src = tEnv.sqlQuery("select * from hive.source_db.test_table_pt_1 where pt = 0");
		// first check execution plan to ensure partition prunning works
		String[] explain = tEnv.explain(src).split("==.*==\n");
		assertEquals(4, explain.length);
		String abstractSyntaxTree = explain[1];
		String optimizedLogicalPlan = explain[2];
		String physicalExecutionPlan = explain[3];
		assertTrue(abstractSyntaxTree, abstractSyntaxTree.contains(
				"HiveTableSource(year, value, pt) TablePath: source_db.test_table_pt_1, PartitionPruned: false, PartitionNums: 2"));
		assertTrue(optimizedLogicalPlan, optimizedLogicalPlan.contains(
				"HiveTableSource(year, value, pt) TablePath: source_db.test_table_pt_1, PartitionPruned: true, PartitionNums: 1"));
		assertTrue(physicalExecutionPlan, physicalExecutionPlan.contains(
				"HiveTableSource(year, value, pt) TablePath: source_db.test_table_pt_1, PartitionPruned: true, PartitionNums: 1"));
		// second check execute results
		List<Row> rows = TableUtils.collectToList(src);
		assertEquals(2, rows.size());
		Object[] rowStrings = rows.stream().map(Row::toString).sorted().toArray();
		assertArrayEquals(new String[]{"2014,3,0", "2014,4,0"}, rowStrings);
	}

	@Test
	public void testProjectionPushDown() throws Exception {
		hiveShell.execute("create table src(x int,y string) partitioned by (p1 bigint, p2 string)");
		final String catalogName = "hive";
		try {
			HiveTestUtils.createTextTableInserter(hiveShell, "default", "src")
					.addRow(new Object[]{1, "a"})
					.addRow(new Object[]{2, "b"})
					.commit("p1=2013, p2='2013'");
			HiveTestUtils.createTextTableInserter(hiveShell, "default", "src")
					.addRow(new Object[]{3, "c"})
					.commit("p1=2014, p2='2014'");
			TableEnvironment tableEnv = HiveTestUtils.createTableEnv();
			tableEnv.registerCatalog(catalogName, hiveCatalog);
			Table table = tableEnv.sqlQuery("select p1, count(y) from hive.`default`.src group by p1");
			String[] explain = tableEnv.explain(table).split("==.*==\n");
			assertEquals(4, explain.length);
			String logicalPlan = explain[2];
			String physicalPlan = explain[3];
			String expectedExplain =
					"HiveTableSource(x, y, p1, p2) TablePath: default.src, PartitionPruned: false, PartitionNums: 2, ProjectedFields: [2, 1]";
			assertTrue(logicalPlan, logicalPlan.contains(expectedExplain));
			assertTrue(physicalPlan, physicalPlan.contains(expectedExplain));

			List<Row> rows = TableUtils.collectToList(table);
			assertEquals(2, rows.size());
			Object[] rowStrings = rows.stream().map(Row::toString).sorted().toArray();
			assertArrayEquals(new String[]{"2013,2", "2014,1"}, rowStrings);
		} finally {
			hiveShell.execute("drop table src");
		}
	}

	@Test
	public void testLimitPushDown() throws Exception {
		hiveShell.execute("create table src (a string)");
		final String catalogName = "hive";
		try {
			HiveTestUtils.createTextTableInserter(hiveShell, "default", "src")
						.addRow(new Object[]{"a"})
						.addRow(new Object[]{"b"})
						.addRow(new Object[]{"c"})
						.addRow(new Object[]{"d"})
						.commit();
			//Add this to obtain correct stats of table to avoid FLINK-14965 problem
			hiveShell.execute("analyze table src COMPUTE STATISTICS");
			TableEnvironment tableEnv = HiveTestUtils.createTableEnv();
			tableEnv.registerCatalog(catalogName, hiveCatalog);
			Table table = tableEnv.sqlQuery("select * from hive.`default`.src limit 1");
			String[] explain = tableEnv.explain(table).split("==.*==\n");
			assertEquals(4, explain.length);
			String logicalPlan = explain[2];
			String physicalPlan = explain[3];
			String expectedExplain = "HiveTableSource(a) TablePath: default.src, PartitionPruned: false, " +
									"PartitionNums: 1, LimitPushDown true, Limit 1";
			assertTrue(logicalPlan.contains(expectedExplain));
			assertTrue(physicalPlan.contains(expectedExplain));

			List<Row> rows = TableUtils.collectToList(table);
			assertEquals(1, rows.size());
			Object[] rowStrings = rows.stream().map(Row::toString).sorted().toArray();
			assertArrayEquals(new String[]{"a"}, rowStrings);
		} finally {
			hiveShell.execute("drop table src");
		}
	}

	@Test
	public void testParallelismSetting() {
		final String catalogName = "hive";
		final String dbName = "source_db";
		final String tblName = "test_parallelism";
		hiveShell.execute("CREATE TABLE source_db.test_parallelism " +
				"(year STRING, value INT) partitioned by (pt int);");
		HiveTestUtils.createTextTableInserter(hiveShell, dbName, tblName)
				.addRow(new Object[]{"2014", 3})
				.addRow(new Object[]{"2014", 4})
				.commit("pt=0");
		HiveTestUtils.createTextTableInserter(hiveShell, dbName, tblName)
				.addRow(new Object[]{"2015", 2})
				.addRow(new Object[]{"2015", 5})
				.commit("pt=1");
		TableEnvironment tEnv = HiveTestUtils.createTableEnv();
		tEnv.registerCatalog(catalogName, hiveCatalog);
		Table table = tEnv.sqlQuery("select * from hive.source_db.test_parallelism");
		PlannerBase planner = (PlannerBase) ((TableEnvironmentImpl) tEnv).getPlanner();
		RelNode relNode = planner.optimize(TableTestUtil.toRelNode(table));
		ExecNode execNode = planner.translateToExecNodePlan(toScala(Collections.singletonList(relNode))).get(0);
		@SuppressWarnings("unchecked")
		Transformation transformation = execNode.translateToPlan(planner);
		Assert.assertEquals(2, transformation.getParallelism());
	}
}
