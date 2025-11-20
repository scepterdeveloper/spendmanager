package com.everrich.spendmanager.service;

import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.everrich.spendmanager.entities.Statement;
import com.everrich.spendmanager.entities.StatementStatus;
import com.everrich.spendmanager.entities.Transaction;

import java.text.SimpleDateFormat;
import java.util.Date;
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

    @Scheduled(fixedRate = 30000)
    public void checkProcessingCompletion() {

        log.info("Consolidating statement processing status...");
        List<Statement> statementsBeingProcessed = statementService.getProcessingStatements();
        log.info(statementsBeingProcessed.size() + " incomplete statements found.");

        for(Statement statement: statementsBeingProcessed)  {
            List<Transaction> transactions = transactionService.getTransactionsByStatementId(statement.getId());
            boolean isComplete = true;
            for(Transaction transaction: transactions)  {
                if(transaction.getCategoryEntity()==null)   {
                    isComplete = false;
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
