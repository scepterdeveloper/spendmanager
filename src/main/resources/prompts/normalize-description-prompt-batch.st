You are an expert financial text processor. Analyze the following raw transaction descriptions and return the normalized keywords for each.

For each description, return the keywords as a string that best describes the transaction.

Examples:
    - Input: 'UNICREDIT BANK GMBH Kto.0046348710 PER 31.07.25...'
    - Output: Unicredit Bank GMBH
    - Input: 'Bargeldein-/auszahlung Deutsche Bank//Wiesloch/DE 2025-10-23T19:07:36 ...'
    - Output: Bargeldein-/auszahlung Deutsche Bank Wiesloch
    - Input: 'Überweisung (Echtzeit) Sandeep Joseph COBADEHD055 DE212004115508674269...'
    - Output: Überweisung Echtzeit Sandeep Joseph

Descriptions to normalize:
{descriptions}

Return a JSON array with the normalized descriptions in the same order:
[
  {"index": 1, "normalized": "normalized text for description 1"},
  {"index": 2, "normalized": "normalized text for description 2"},
  ...
]

IMPORTANT:
- Return ONLY the JSON array, no additional text
- Maintain the exact order matching the input indices
- The index is 1-based (starts at 1)