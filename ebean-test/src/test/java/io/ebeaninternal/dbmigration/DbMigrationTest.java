package io.ebeaninternal.dbmigration;

import io.ebean.*;
import io.ebean.annotation.IgnorePlatform;
import io.ebean.annotation.Platform;
import io.ebean.config.DatabaseConfig;
import io.ebean.config.dbplatform.DbHistorySupport;
import io.ebean.datasource.pool.ConnectionPool;
import misc.migration.v1_1.EHistory;
import misc.migration.v1_1.EHistory2;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This testcase tries to apply the migrationtests that are genearated by {@link DbMigrationGenerateTest}.
 *
 * It does also some basic checks, if the migration is applied correctly.
 *
 * Please note, that this test requires the scripts generated by DbMigrationGenerateTest. So you may have to execute this test
 * first.
 *
 * @author Roland Praml, FOCONIS AG
 *
 */
public class DbMigrationTest extends BaseTestCase {

  private void runScript(String scriptName) {
    URL url = getClass().getResource("/migrationtest/dbmigration/" + server().platform().name().toLowerCase() + "/" + scriptName);
    assert url != null : scriptName +  " not found";
    server().script().run(url);
  }

  @Test
  public void lastVersion() {
    File d = new File("src/test/resources/migrationtest/dbmigration/h2");
    assertThat(LastMigration.lastVersion(d, null)).isEqualTo("1.4");
    assertThat(LastMigration.nextVersion(d, null, false)).isEqualTo("1.5");
    assertThat(LastMigration.nextVersion(d, null, true)).isEqualTo("1.4");
  }

  @IgnorePlatform({
    // Yugabyte does not see column updates on table alters:
    // update table T set C = 'value'; alter table T alter column C set not null -> error column C has null values
    // do we need a commit after update?
    Platform.YUGABYTE,
  })
  @Test
  public void testRunMigration() throws IOException, SQLException {
    // Shutdown and reconnect - this prevents postgres from lock up
    ((ConnectionPool)server().dataSource()).offline();
    ((ConnectionPool)server().dataSource()).online();
    cleanup("migtest_ckey_assoc",
        "migtest_ckey_detail",
        "migtest_ckey_parent",
        "migtest_e_basic",
        "migtest_e_enum",
        "migtest_e_history",
        "migtest_e_history2",
        "migtest_e_history3",
        "migtest_e_history4",
        "migtest_e_history5",
        "migtest_e_history6",
        "migtest_e_ref",
        "migtest_e_softdelete",
        "migtest_e_user",
        "migtest_fk_cascade",
        "migtest_fk_cascade_one",
        "migtest_fk_none",
        "migtest_fk_none_via_join",
        "migtest_fk_one",
        "migtest_fk_set_null",
        "migtest_mtm_c",
        "migtest_mtm_m",
        "migtest_mtm_m_phone_numbers",
        "migtest_mtm_c_migtest_mtm_m",
        "migtest_mtm_m_migtest_mtm_c",
        "migtest_oto_child",
        "migtest_oto_master");
    ((ConnectionPool)server().dataSource()).offline();
    ((ConnectionPool)server().dataSource()).online();

    if (isSqlServer() || isMariaDB() || isMySql() || isHana()) {
      runScript("I__create_procs.sql");
    }

    runScript("1.0__initial.sql");

    if (isClickHouse()) {
      // ClickHouse does not support transactions, so we cannot do update statements
      // Add column is also not implemented. So exit here
      return;
    } else if (isOracle() || isHana()) {
      SqlUpdate update = server().sqlUpdate("insert into migtest_e_basic (id, old_boolean, user_id) values (1, :false, 1)");
      update.setParameter("false", false);
      assertThat(server().execute(update)).isEqualTo(1);

      update = server().sqlUpdate("insert into migtest_e_basic (id, old_boolean, user_id) values (2, :true, 1)");
      update.setParameter("true", true);
      assertThat(server().execute(update)).isEqualTo(1);
    } else {
      SqlUpdate update = server().sqlUpdate("insert into migtest_e_basic (id, old_boolean, user_id) values (1, :false, 1), (2, :true, 1)");
      update.setParameter("false", false);
      update.setParameter("true", true);

      assertThat(server().execute(update)).isEqualTo(2);
    }

    createHistoryEntities();
    if (isOracle()) {
      // Oracle does not like to convert varchar to integer
      // ORA-01439. "column to be modified must be empty to change datatype".
      // If the current table is not empty, you may have to create a temp-table
      // with correct data types or do it with DBMS_REDEFINITION - to get the test
      // working, we clear all data in the table
      server().sqlUpdate("delete from migtest_e_history").execute();
      server().sqlUpdate("delete from migtest_e_history4").execute();
    }

    // Run migration
    runScript("1.1.sql");
    List<SqlRow> result = server().sqlQuery("select * from migtest_e_basic order by id").findList();
    assertThat(result).hasSize(2);

    SqlRow row = result.get(0);
    assertThat(row.keySet()).contains("old_boolean", "old_boolean2");

    assertThat(row.getInteger("id")).isEqualTo(1);
    assertThat(row.getBoolean("old_boolean")).isFalse();
    assertThat(row.getBoolean("new_boolean_field")).isFalse(); // test if update old_boolean -> new_boolean_field works well

    assertThat(row.getString("new_string_field")).isEqualTo("foo'bar");
    assertThat(row.getBoolean("new_boolean_field2")).isTrue();
    //assertThat(row.getTimestamp("some_date")).isCloseTo(new Date(), 86_000); // allow 1 minute delta

    row = result.get(1);
    assertThat(row.getInteger("id")).isEqualTo(2);
    assertThat(row.getBoolean("old_boolean")).isTrue();
    assertThat(row.getBoolean("new_boolean_field")).isTrue(); // test if update old_boolean -> new_boolean_field works well

    assertThat(row.getString("new_string_field")).isEqualTo("foo'bar");
    assertThat(row.getBoolean("new_boolean_field2")).isTrue();
    //assertThat(row.getTimestamp("some_date")).isCloseTo(new Date(), 60_000); // allow 1 minute delta

    testVersioning();
    if (isSqLite()) {
      // SqLite does not support drops on columns with foreign keys, so we end with the test here.
      return;
    }
    runScript("1.2__dropsFor_1.1.sql");

    // Oracle caches the statement and does not detect schema change. It fails with
    // an ORA-01007
    result = server().sqlQuery("select * from migtest_e_basic order by id,status").findList();
    assertThat(result).hasSize(2);
    row = result.get(0);
    assertThat(row.keySet()).doesNotContain("old_boolean", "old_boolean2");

    if (isYugabyte()) {
      // there are some unsupported alter commands in 1.3 - so we exit here
      return;
    }
    runScript("1.3.sql");
    runScript("1.4__dropsFor_1.3.sql");

    // now DB structure should be the same as v1_0 - perform a diffent query.
    result = server().sqlQuery("select * from migtest_e_basic order by id,name").findList();
    assertThat(result).hasSize(2);
    row = result.get(0);
    assertThat(row.keySet()).contains("old_boolean", "old_boolean2");
  }

  // do some history tests with V1.1 models
  private void testVersioning() {
    if (isOracle()) {
      System.err.println("FIXME: Oracle history support seems to be broken");
      return;
    }
    DbHistorySupport history = server().pluginApi().databasePlatform().getHistorySupport();
    if (history == null) {
      return;
    }
    DatabaseConfig config = new DatabaseConfig();
    config.setName(server().name());
    config.loadFromProperties(server().pluginApi().config().getProperties());
    config.setDataSource(server().dataSource());
    config.setReadOnlyDataSource(server().dataSource());
    config.setDdlGenerate(false);
    config.setDdlRun(false);
    config.setRegister(false);
    config.setPackages(Collections.singletonList("misc.migration.v1_1"));

    Database tmpServer = DatabaseFactory.create(config);
    try {
      EHistory hist = new misc.migration.v1_1.EHistory();
      hist.setId(2);
      hist.setTestString(42L);
      tmpServer.save(hist);
      hist = tmpServer.find(EHistory.class).where().eq("testString", 42L).findOne();
      assert hist != null;
      hist.setTestString(45L);
      tmpServer.save(hist);

      List<Version<EHistory>> versions = tmpServer.find(EHistory.class).setId(hist.getId())
          .findVersionsBetween(Timestamp.valueOf("1970-01-01 00:00:00"), Timestamp.valueOf("2100-01-01 00:00:00"));
      assertThat(versions).hasSize(2);
      assertThat(versions.get(0).getDiff().toString()).as("using platform: %s", server().platform())
        .isEqualTo("{testString=45,42}");

      EHistory2 hist2 = new misc.migration.v1_1.EHistory2();
      hist2.setId(2);
      hist2.setTestString("foo1");
      hist2.setTestString2("bar1");
      hist2.setTestString3("baz1");
      tmpServer.save(hist2);
      hist2.setTestString("foo2");
      hist2.setTestString2("bar2");
      tmpServer.save(hist2);

      List<Version<EHistory2>> versions2 = tmpServer.find(EHistory2.class).setId(hist.getId())
          .findVersionsBetween(Timestamp.valueOf("1970-01-01 00:00:00"), Timestamp.valueOf("2100-01-01 00:00:00"));
      assertThat(versions2).hasSize(2);

      // not all platforms will support history exclusions
      switch (server().platform()) {
      case H2: // Trigger ignores HistoryExclude
      case SQLSERVER17: // these DBs are 'standard based' so they also do not support HistoryExclude
      case MARIADB:
      case HANA:
        assertThat(versions2.get(0).getDiff().toString()).as("using platform: %s, versions2:%s", server().platform(), versions2)
            .contains("testString=foo2,foo1")
            .contains("testString2=bar2,bar1");
        break;
      case MYSQL:
      case POSTGRES:
      case YUGABYTE:
        assertThat(versions2.get(0).getDiff().toString()).as("using platform: %s, versions2:%s", server().platform(), versions2)
            .contains("testString=foo2,foo1")
            .contains("testString2=bar2,null");
        break;
      default:
        throw new IllegalArgumentException(server().platform() + " not expected");
      }

    } finally {
      tmpServer.shutdown(false, false);
    }
  }

  private void createHistoryEntities() {
    SqlUpdate update = server().sqlUpdate("insert into migtest_e_history (id, test_string) values (1, '42')");
    assertThat(server().execute(update)).isEqualTo(1);
    update = server().sqlUpdate("update migtest_e_history set test_string = '45' where id = 1");
    assertThat(server().execute(update)).isEqualTo(1);

    update = server().sqlUpdate("insert into migtest_e_history2 (id, test_string, obsolete_string1, obsolete_string2) values (1, 'foo', 'bar', null)");
    assertThat(server().execute(update)).isEqualTo(1);
    update = server().sqlUpdate("update migtest_e_history2 set test_string = 'baz' where id = 1");
    assertThat(server().execute(update)).isEqualTo(1);

    update = server().sqlUpdate("insert into migtest_e_history3 (id, test_string) values (1, '42')");
    assertThat(server().execute(update)).isEqualTo(1);
    update = server().sqlUpdate("update migtest_e_history3 set test_string = '45' where id = 1");
    assertThat(server().execute(update)).isEqualTo(1);

    update = server().sqlUpdate("insert into migtest_e_history4 (id, test_number) values (1, 42)");
    assertThat(server().execute(update)).isEqualTo(1);
    update = server().sqlUpdate("update migtest_e_history4 set test_number = 45 where id = 1");
    assertThat(server().execute(update)).isEqualTo(1);

    update = server().sqlUpdate("insert into migtest_e_history5 (id, test_number) values (1, 42)");
    assertThat(server().execute(update)).isEqualTo(1);
    update = server().sqlUpdate("update migtest_e_history5 set test_number = 45 where id = 1");
    assertThat(server().execute(update)).isEqualTo(1);

    update = server().sqlUpdate("insert into migtest_e_history6 (id, test_number1, test_number2) values (1, 2, 7)");
    assertThat(server().execute(update)).isEqualTo(1);
    update = server().sqlUpdate("update migtest_e_history6 set test_number2 = 45 where id = 1");
    assertThat(server().execute(update)).isEqualTo(1);
  }

  private void cleanup(String ... tables) {

    final boolean sqlServer = isSqlServer();
    final boolean postgres = isPostgres();

    StringBuilder sb = new StringBuilder();
    for (String table : tables) {
      // simple and stupid try to execute all commands on all dialects.
      if (sqlServer) {
        sb.append("alter table ").append(table).append(" set ( system_versioning = OFF  );\n");
        sb.append("alter table ").append(table).append(" drop system versioning;\n");
      }
      if (postgres) {
        sb.append("drop table ").append(table).append(" cascade;\n");
        sb.append("drop table ").append(table).append("_history cascade;\n");
      } else {
        sb.append("drop table ").append(table).append(";\n");
        sb.append("drop table ").append(table).append("_history;\n");
      }
      sb.append("drop view ").append(table).append("_with_history;\n");
      sb.append("drop sequence ").append(table).append("_seq;\n");
    }
    server().script().runScript("cleanup", sb.toString(), true);
    server().script().runScript("cleanup", sb.toString(), true);
  }
}
