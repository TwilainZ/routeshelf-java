import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location("signing", pathlib.Path(__file__).with_name("select-signing-identity.py"))
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)


class SigningIdentityTests(unittest.TestCase):
    development = '1) ' + 'A' * 40 + ' "Apple Development: Fixture"\n'
    distribution = '2) ' + 'B' * 40 + ' "Developer ID Application: Fixture"\n'

    def test_auto_prefers_valid_distribution_identity(self):
        self.assertEqual(signing.select(self.development + self.distribution), ("developer-id", "B" * 40))

    def test_explicit_mode_never_falls_back_to_ad_hoc(self):
        with self.assertRaises(ValueError):
            signing.select(self.development, "developer-id")

    def test_multiple_teams_require_explicit_selection(self):
        other = self.distribution.replace("B" * 40, "C" * 40)
        with self.assertRaises(ValueError):
            signing.select(self.distribution + other)
        self.assertEqual(signing.select(self.distribution + other, "auto", "c" * 40), ("developer-id", "C" * 40))

    def test_unknown_or_wrong_certificate_is_rejected(self):
        for fingerprint in ("D" * 40, "B" * 39, "$(untrusted)"):
            with self.assertRaises(ValueError):
                signing.select(self.distribution, "auto", fingerprint)
        with self.assertRaises(ValueError):
            signing.select(self.distribution, "development", "B" * 40)

    def test_no_key_reports_preview_and_never_claims_distribution(self):
        self.assertEqual(signing.select("0 valid identities found"), ("ad-hoc", "-"))
        with self.assertRaises(ValueError):
            signing.select("0 valid identities found", "developer-id")

    def test_explicit_preview_cannot_hide_selected_certificate(self):
        with self.assertRaises(ValueError):
            signing.select(self.distribution, "ad-hoc", "B" * 40)


if __name__ == "__main__":
    unittest.main()
