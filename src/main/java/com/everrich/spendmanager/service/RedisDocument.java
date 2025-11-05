package com.everrich.spendmanager.service;

import java.util.Hashtable;

public class RedisDocument {

    private String documentId;
    private Hashtable<String, String> fields;

    public Hashtable<String, String> getFields() {
        return fields;
    }

    public void setFields(Hashtable<String, String> fields) {
        this.fields = fields;
    }

    public RedisDocument(){
        fields = new Hashtable<>();
    }

    public String getDocumentId() {
        return documentId;
    }
    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }
    public void addField(String name, String value)   {
        fields.put(name, value);
    }
}
