You are an expert financial text processor. Analyze the following raw transaction description and return the keywords as a string that best describes the transaction. 
Below are some samples:

Examples:
    - Input: 'UNICREDIT BANK GMBH Kto.0046348710 PER 31.07.25...'
    - Output: Unicredit Bank GMBH
    - Input: 'Bargeldein-/auszahlung Deutsche Bank//Wiesloch/DE 2025-10-23T19:07:36 ...'
    - Output: Bargeldein-/auszahlung Deutsche Bank Wiesloch
    - Input: 'Überweisung (Echtzeit) Sandeep Joseph COBADEHD055 DE212004115508674269...'
    - Output: Überweisung Echtzeit Sandeep Joseph

Raw Description: {transactionDescription}
            """;
