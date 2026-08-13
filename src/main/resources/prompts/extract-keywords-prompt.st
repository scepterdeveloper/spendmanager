Extract the meaningful keywords from this bank transaction description that would help identify similar transactions in the future.

REMOVE these technical identifiers (they change between transactions):
- IBANs (e.g., DE89370400440532013000)
- BICs/SWIFT codes (e.g., COBADEHD055)
- Account numbers (e.g., Kto.0046348710)
- Reference numbers (End-to-End-Ref, Mandatsref, Gläubiger-ID)
- Dates and timestamps (e.g., 30.04, 2025-10-23)
- Transaction IDs and internal references
- Random alphanumeric codes

KEEP these meaningful elements:
- Company/merchant names (e.g., "PayPal Europe S.a.r.l.", "REWE", "Amazon EU")
- Transaction type indicators (e.g., "SEPA-BASISLASTSCHRIFT", "GEHALT/LOHN", "Überweisung")
- Meaningful descriptive phrases (e.g., "Ihr Einkauf bei", "wiederholend")
- Person names if they appear (e.g., for transfers)

Transaction Description:
"{description}"

Return ONLY the extracted keywords as a comma-separated list. No explanation, no quotes around the entire response.

Keywords: