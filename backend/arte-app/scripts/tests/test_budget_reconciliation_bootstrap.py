import importlib.util
import pathlib
import unittest
path = pathlib.Path(__file__).parents[1] / 'arte-budget-reconciliation-bootstrap.py'
spec = importlib.util.spec_from_file_location('bootstrap', path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class BootstrapTest(unittest.TestCase):
    def test_ledger_and_public_keys_have_distinct_framing(self):
        self.assertNotEqual(module.digest(['a', 'b']), module.digest(['ab']))
        self.assertNotEqual(module.digest(['a'], True), module.digest(['a']))

    def test_only_explicit_one_account_governance_is_seeded(self):
        sql = module.generate('tenant', 'workspace', 'budget', 'owner', 'governor', 'issuer', 2, 'reviewed-op')
        self.assertIn('owner_key=', sql)
        self.assertIn('wm.generation=2', sql)
        self.assertIn('ROLLBACK; RESIGNAL', sql)
        self.assertIn("'budget-account:governor-provisioned'", sql)
        self.assertNotIn('UPDATE arte_ai_account', sql)
        self.assertNotIn('UPDATE arte_ai_reservation', sql)
        self.assertNotIn('INSERT INTO arte_rbac_relation', sql)
        self.assertNotIn('budget-account:reconcile', sql)

    def test_sql_strings_are_hex_encoded_and_invalid_identifiers_fail(self):
        self.assertNotIn("'a' OR", module.literal("a' OR 1=1"))
        with self.assertRaises(ValueError):
            module.generate('', 'workspace', 'budget', 'owner', 'governor', 'issuer', 0, 'op')
        with self.assertRaises(ValueError):
            module.generate('t', 'w', 'b', 'o', 'g', 'i', -1, 'op')
