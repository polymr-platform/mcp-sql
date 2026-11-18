package app;

import static app.SqlService.SqlType.DML;
import static app.SqlService.SqlType.SELECT;
import static app.SqlService.SqlType.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class SqlServiceSqlTypeTest {
	private void assertType(SqlService.SqlType expected, String sql) {
		assertEquals(expected, SqlService.SqlType.fromSql(sql.toLowerCase(Locale.ROOT)), sql);
	}

	@Test
	void classifiesReadOnlyCtes() {
		assertType(SELECT, "WITH rows AS (SELECT id FROM items) SELECT * FROM rows");
		assertType(SELECT, "WITH a AS (SELECT 1), b AS (SELECT * FROM a) SELECT * FROM b");
		assertType(SELECT, "WITH RECURSIVE nums(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM nums WHERE n < 3) SELECT * FROM nums");
		assertType(SELECT, "WITH rows AS MATERIALIZED (SELECT id FROM items) SELECT * FROM rows");
		assertType(SELECT, "WITH rows AS NOT MATERIALIZED (SELECT id FROM items) SELECT * FROM rows");
		assertType(SELECT, "WITH rows AS /* hint */ MATERIALIZED /* hint */ (SELECT 'update items' AS value) SELECT * FROM rows");
		assertType(SELECT, "WITH a AS MATERIALIZED (SELECT 1), b AS NOT MATERIALIZED (SELECT * FROM a) SELECT * FROM b");
		assertType(SELECT, "WITH collector_inactivations AS MATERIALIZED (SELECT DISTINCT cdm_instance_id FROM cdm_instance_sync_diffs) SELECT count(*) FROM collector_inactivations");
	}

	@Test
	void classifiesDataModifyingCtesAsDmlEvenWithSelectOutside() {
		assertType(DML, "WITH changed AS (UPDATE items SET active = false RETURNING id) SELECT * FROM changed");
		assertType(DML, "WITH changed AS MATERIALIZED (DELETE FROM items RETURNING id) SELECT * FROM changed");
		assertType(DML, "WITH changed AS NOT MATERIALIZED (INSERT INTO items(id) VALUES (1) RETURNING id) SELECT * FROM changed");
		assertType(DML, "WITH first AS (SELECT 1), changed AS (UPDATE items SET active = false RETURNING id) SELECT * FROM changed");
		assertType(DML, "WITH changed AS (DELETE FROM items RETURNING id), other AS (SELECT 1) SELECT * FROM other");
		assertType(DML, "WITH rows AS (SELECT id FROM items) UPDATE items SET active = false WHERE id IN (SELECT id FROM rows)");
		assertType(DML, "WITH changed AS (/* harmless */ UPDATE items SET active = false RETURNING id) SELECT * FROM changed");
		assertType(DML, "WITH parameters AS MATERIALIZED ( SELECT 'd72a1b8e-6aed-4401-a733-1345e83502c9'::uuid AS process_id, timezone('UTC', current_timestamp) AS repaired_at ), target AS MATERIALIZED ( SELECT pi.id AS process_id, pi.current_state_id AS state_id, pa.id AS action_id, last_action.server_owner, p.repaired_at FROM parameters p JOIN process_instances pi ON pi.id = p.process_id JOIN process_versions pv ON pv.id = pi.process_version_id JOIN process_definitions pd ON pd.id = pv.process_definition_id JOIN process_states ps ON ps.id = pi.current_state_id JOIN process_actions pa ON pa.process_state_id = ps.id CROSS JOIN LATERAL ( SELECT pai.server_owner FROM process_action_instances pai JOIN process_state_instances psi ON psi.id = pai.process_state_instance_id WHERE psi.process_instance_id = pi.id ORDER BY pai.action_index DESC LIMIT 1 ) last_action WHERE pd.code = 'registration' AND pv.version = 191 AND ps.code = 'registration-part2' AND pi.process_state = 'SUCCEEDED' AND pi.stopped IS NOT NULL AND pa.name = 'Push request to dynamics' AND pa.service_id = 'myBebat.services.registration.create' AND EXISTS ( SELECT 1 FROM process_data d WHERE d.process_instance_id = pi.id AND d.active = true AND d.name = 'dynamicsRegistrationId' AND replace(d.value, '-', '') = '1af29bcb4d53f011877a7ced8d4664fd' ) AND NOT EXISTS ( SELECT 1 FROM process_state_instances open_state WHERE open_state.process_instance_id = pi.id AND open_state.stopped IS NULL ) ), reactivated AS ( UPDATE process_instances pi SET process_state = 'RUNNING', stopped = NULL FROM target t WHERE pi.id = t.process_id RETURNING pi.id, pi.current_state_id ), created_state AS ( INSERT INTO process_state_instances ( id, process_state_id, started, stopped, process_instance_id, reason ) SELECT gen_random_uuid(), t.state_id, t.repaired_at, NULL, r.id, 'BEBINT-969 retroactive qualification' FROM reactivated r JOIN target t ON t.process_id = r.id RETURNING id, process_instance_id ), created_action AS ( INSERT INTO process_action_instances ( id, started, stopped, process_state_instance_id, process_action_id, process_state, server_owner, reason, reset ) SELECT gen_random_uuid(), t.repaired_at, t.repaired_at, cs.id, t.action_id, 'SUCCEEDED', t.server_owner, 'BEBINT-969: Dynamics push already completed', false FROM created_state cs JOIN target t ON t.process_id = cs.process_instance_id RETURNING id, process_state_instance_id ) SELECT CASE WHEN count(*) = 1 THEN 'APPLIED' ELSE 'NOT_APPLIED' END AS result, max(process_state_instance_id::text) AS process_state_instance_id, max(id::text) AS process_action_instance_id FROM created_action");
	}

	@Test
	void doesNotTreatLiteralsOrIdentifiersAsWrites() {
		assertType(SELECT, "WITH rows AS (SELECT 'delete from items' AS message) SELECT * FROM rows");
		assertType(SELECT, "WITH rows AS (/* update items */ SELECT 1) SELECT * FROM rows");
		assertType(SELECT, "WITH rows AS (SELECT \"update\" FROM items) SELECT * FROM rows");
		assertType(UNKNOWN, "WITH rows AS NOT OTHER (SELECT 1) SELECT * FROM rows");
	}
}
