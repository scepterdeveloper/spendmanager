package com.everrich.spendmanager.service;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.everrich.spendmanager.entities.Statement;
import com.everrich.spendmanager.entities.StatementStatus;
import com.everrich.spendmanager.entities.Transaction;
import com.everrich.spendmanager.entities.TransactionCategorizationStatus;

import java.text.SimpleDateFormat;
import java.util.List;

import org.slf4j.Logger;

@Component
public class StatementStatusConsolidator {

    private static final Logger log = LoggerFactory.getLogger(StatementStatusConsolidator.class);
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
    private StatementService statementService;
    private TransactionService transactionService;

    public StatementStatusConsolidator(StatementService statementService, TransactionService transactionService)    {
        this.statementService = statementService;
        this.transactionService = transactionService;
    }

    //@Scheduled(fixedRate = 30000)
    public void checkProcessingCompletion() {

        log.info("Consolidating statement processing status...");
        List<Statement> statementsBeingProcessed = statementService.getStatementsBeingCategorized();
        log.info(statementsBeingProcessed.size() + " incomplete statements found.");

        for(Statement statement: statementsBeingProcessed)  {
            List<Transaction> transactions = transactionService.getTransactionsByStatementId(statement.getId());
            log.info("Transactions for statement " + statement.getOriginalFileName() + " - " + transactions.size());
            boolean isComplete = true;

            for(Transaction transaction: transactions)  {
                if(transaction.getCategorizationStatus()==TransactionCategorizationStatus.NOT_CATEGORIZED)   {
                    isComplete = false;
                    log.info("Transaction found with NO CATEGORIZATION - " + transaction.getDescription());
                    break;
                }
            }

            if (isComplete) {
                log.info("Processing completed for statment {}",  statement.getId() + " - " + statement.getOriginalFileName());
                statement.setStatus(StatementStatus.COMPLETED);
                statementService.saveStatement(statement);
            }
        }
    }
}
