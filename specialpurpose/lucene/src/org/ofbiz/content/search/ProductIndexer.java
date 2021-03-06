/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.ofbiz.content.search;

import java.io.File;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.lucene.document.IntField;
import org.apache.lucene.document.LongField;
import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.GeneralException;
import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.content.data.DataResourceWorker;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.condition.EntityOperator;
import org.ofbiz.entity.util.EntityUtil;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoubleField;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;

public class ProductIndexer extends Thread {

    public static final String module = ProductIndexer.class.getName();

    private static Map<Delegator, ProductIndexer> productIndexerMap = new HashMap<Delegator, ProductIndexer>();
    private LinkedBlockingQueue<String> productIndexQueue = new LinkedBlockingQueue<String>();
    private Delegator delegator;
    private Directory indexDirectory;
    private IndexWriterConfig indexWriterConfiguration;
    private static final String NULL_STRING = "NULL";
    // TODO: Move to property file
    private static final int UNCOMMITTED_DOC_LIMIT = 100;

    private ProductIndexer(Delegator delegator) {
        this.delegator = delegator;
        Analyzer analyzer = new StandardAnalyzer(SearchWorker.LUCENE_VERSION);
        this.indexWriterConfiguration = new IndexWriterConfig(SearchWorker.LUCENE_VERSION, analyzer);
        try {
            this.indexDirectory = FSDirectory.open(new File(SearchWorker.getIndexPath("products")));
        } catch (CorruptIndexException e) {
            Debug.logError("Corrupted lucene index: "  + e.getMessage(), module);
        } catch (LockObtainFailedException e) {
            Debug.logError("Could not obtain Lock on lucene index "  + e.getMessage(), module);
        } catch (IOException e) {
            Debug.logError(e.getMessage(), module);
        }
    }

    public static synchronized ProductIndexer getInstance(Delegator delegator) {
        ProductIndexer productIndexer = productIndexerMap.get(delegator);
        if (productIndexer == null) {
            productIndexer = new ProductIndexer(delegator);
            productIndexer.setName("ProductIndexer_" + delegator.getDelegatorName());
            productIndexer.start();
            productIndexerMap.put(delegator, productIndexer);
        }
        return productIndexer;
    }

    @Override
    public void run() {
        IndexWriter indexWriter = null;
        int uncommittedDocs = 0;
        while (true) {
            String productId;
            try {
                // Execution will pause here until the queue receives a product for indexing
                productId = productIndexQueue.take();
            } catch (InterruptedException e) {
                Debug.logError(e, module);
                if (indexWriter != null) {
                    try {
                        indexWriter.close();
                        indexWriter = null;
                    } catch(IOException ioe) {
                        Debug.logError(ioe, module);
                    }
                }
                break;
            }
            Document productDocument = this.prepareProductDocument(productId);
            Term documentIdentifier = new Term("productId", productId);
            if (indexWriter == null) {
                try {
                    indexWriter  = new IndexWriter(this.indexDirectory, this.indexWriterConfiguration);
                } catch (CorruptIndexException e) {
                    Debug.logError("Corrupted lucene index: "  + e.getMessage(), module);
                    break;
                } catch (LockObtainFailedException e) {
                    Debug.logError("Could not obtain Lock on lucene index "  + e.getMessage(), module);
                    // TODO: put the thread to sleep waiting for the locked to be released
                    break;
                } catch (IOException e) {
                    Debug.logError(e.getMessage(), module);
                    break;
                }
            }
            try {
                if (productDocument == null) {
                    indexWriter.deleteDocuments(documentIdentifier);
                    if (Debug.infoOn()) Debug.logInfo("Deleted Lucene document for product: " + productId, module);
                } else {
                    indexWriter.updateDocument(documentIdentifier, productDocument);
                    if (Debug.infoOn()) Debug.logInfo("Indexed Lucene document for product: " + productId, module);
                }
            } catch(Exception e) {
                Debug.logError(e, "Error processing Lucene document for product: " + productId, module);
                if (productIndexQueue.peek() == null) {
                    try {
                        indexWriter.close();
                        indexWriter = null;
                    } catch(IOException ioe) {
                        Debug.logError(ioe, module);
                    }
                }
                continue;
            }
            uncommittedDocs++;
            if (uncommittedDocs == UNCOMMITTED_DOC_LIMIT || productIndexQueue.peek() == null) {
                // limit reached or queue empty, time to commit
                try {
                    indexWriter.commit();
                } catch (IOException e) {
                    Debug.logError(e, module);
                }
                uncommittedDocs = 0;
            }
            if (productIndexQueue.peek() == null) {
                try {
                    indexWriter.close();
                    indexWriter = null;
                } catch (IOException e) {
                    Debug.logError(e, module);
                }
            }
        }
    }

    public boolean queue(String productId) {
        return productIndexQueue.add(productId);
    }

    private Document prepareProductDocument(String productId) {
        try {
            GenericValue product = delegator.findOne("Product", true, "productId", productId);
            if (product == null) {
                // Return a null document (we will remove the document from the index)
                return null;
            } else {
                if ("Y".equals(product.getString("isVariant")) && "true".equals(UtilProperties.getPropertyValue("prodsearch", "index.ignore.variants"))) {
                    return null;
                }
                Document doc = new Document();
                Timestamp nextReIndex = null;

                // Product Fields
                doc.add(new StringField("productId", productId, Store.YES));
                this.addTextFieldByWeight(doc, "productName", product.getString("productName"), "index.weight.Product.productName", 0, false, "fullText");
                this.addTextFieldByWeight(doc, "internalName", product.getString("internalName"), "index.weight.Product.internalName", 0, false, "fullText");
                this.addTextFieldByWeight(doc, "brandName", product.getString("brandName"), "index.weight.Product.brandName", 0, false, "fullText");
                this.addTextFieldByWeight(doc, "description", product.getString("description"), "index.weight.Product.description", 0, false, "fullText");
                this.addTextFieldByWeight(doc, "longDescription", product.getString("longDescription"), "index.weight.Product.longDescription", 0, false, "fullText");
                //doc.add(new StringField("introductionDate", checkValue(product.getString("introductionDate")), Store.NO));
                doc.add(new LongField("introductionDate", quantizeTimestampToDays(product.getTimestamp("introductionDate")), Store.NO));
                nextReIndex = this.checkSetNextReIndex(product.getTimestamp("introductionDate"), nextReIndex);
                doc.add(new LongField("salesDiscontinuationDate", quantizeTimestampToDays(product.getTimestamp("salesDiscontinuationDate")), Store.NO));
                nextReIndex = this.checkSetNextReIndex(product.getTimestamp("salesDiscontinuationDate"), nextReIndex);
                doc.add(new StringField("isVariant", product.get("isVariant") != null && product.getBoolean("isVariant") ? "true" : "false", Store.NO));

                // ProductFeature Fields, check that at least one of the fields is set to be indexed
                if (!"0".equals(UtilProperties.getPropertyValue("prodsearch", "index.weight.ProductFeatureAndAppl.description", "0")) ||
                        !"0".equals(UtilProperties.getPropertyValue("prodsearch", "index.weight.ProductFeatureAndAppl.abbrev", "0")) ||
                        !"0".equals(UtilProperties.getPropertyValue("prodsearch", "index.weight.ProductFeatureAndAppl.idCode", "0"))) {

                    List<GenericValue> productFeatureAndAppls = delegator.findByAnd("ProductFeatureAndAppl", UtilMisc.toMap("productId", productId), null, false);
                    productFeatureAndAppls = this.filterByThruDate(productFeatureAndAppls);

                    for (GenericValue productFeatureAndAppl: productFeatureAndAppls) {
                        Timestamp fromDate = productFeatureAndAppl.getTimestamp("fromDate");
                        Timestamp thruDate = productFeatureAndAppl.getTimestamp("thruDate");
                        if (fromDate != null && fromDate.after(UtilDateTime.nowTimestamp())) {
                            // fromDate is after now, update reindex date but don't index the feature
                            nextReIndex = this.checkSetNextReIndex(fromDate, nextReIndex);
                            continue;
                        } else if (thruDate != null) {
                            nextReIndex = this.checkSetNextReIndex(thruDate, nextReIndex);
                        }
                        doc.add(new StringField("productFeatureId", productFeatureAndAppl.getString("productFeatureId"), Store.NO));
                        doc.add(new StringField("productFeatureCategoryId", productFeatureAndAppl.getString("productFeatureCategoryId"), Store.NO));
                        doc.add(new StringField("productFeatureTypeId", productFeatureAndAppl.getString("productFeatureTypeId"), Store.NO));
                        this.addTextFieldByWeight(doc, "featureDescription", productFeatureAndAppl.getString("description"), "index.weight.ProductFeatureAndAppl.description", 0, false, "fullText");
                        this.addTextFieldByWeight(doc, "featureAbbreviation", productFeatureAndAppl.getString("abbrev"), "index.weight.ProductFeatureAndAppl.abbrev", 0, false, "fullText");
                        this.addTextFieldByWeight(doc, "featureCode", productFeatureAndAppl.getString("idCode"), "index.weight.ProductFeatureAndAppl.idCode", 0, false, "fullText");
                        // Get the ProductFeatureGroupIds
                        List<GenericValue> productFeatureGroupAppls = delegator.findByAnd("ProductFeatureGroupAppl", UtilMisc.toMap("productFeatureId", productFeatureAndAppl.get("productFeatureId")), null, false);
                        productFeatureGroupAppls = this.filterByThruDate(productFeatureGroupAppls);
                        for (GenericValue productFeatureGroupAppl : productFeatureGroupAppls) {
                            fromDate = productFeatureGroupAppl.getTimestamp("fromDate");
                            thruDate = productFeatureGroupAppl.getTimestamp("thruDate");
                            if (fromDate != null && fromDate.after(UtilDateTime.nowTimestamp())) {
                                // fromDate is after now, update reindex date but don't index the feature
                                nextReIndex = this.checkSetNextReIndex(fromDate, nextReIndex);
                                continue;
                            } else if (thruDate != null) {
                                nextReIndex = this.checkSetNextReIndex(thruDate, nextReIndex);
                            }
                            doc.add(new StringField("productFeatureGroupId", productFeatureGroupAppl.getString("productFeatureGroupId"), Store.NO));
                        }
                    }
                }

                // ProductAttribute Fields
                if (!"0".equals(UtilProperties.getPropertyValue("prodsearch", "index.weight.ProductAttribute.attrName", "0")) ||
                        !"0".equals(UtilProperties.getPropertyValue("prodsearch", "index.weight.ProductAttribute.attrValue", "0"))) {

                    List<GenericValue> productAttributes = delegator.findByAnd("ProductAttribute", UtilMisc.toMap("productId", productId), null, false);
                    for (GenericValue productAttribute: productAttributes) {
                        this.addTextFieldByWeight(doc, "attributeName", productAttribute.getString("attrName"), "index.weight.ProductAttribute.attrName", 0, false, "fullText");
                        this.addTextFieldByWeight(doc, "attributeValue", productAttribute.getString("attrValue"), "index.weight.ProductAttribute.attrValue", 0, false, "fullText");
                    }
                }

                // GoodIdentification
                if (!"0".equals(UtilProperties.getPropertyValue("prodsearch", "index.weight.GoodIdentification.idValue", "0"))) {
                    List<GenericValue> goodIdentifications = delegator.findByAnd("GoodIdentification", UtilMisc.toMap("productId", productId), null, false);
                    for (GenericValue goodIdentification: goodIdentifications) {
                        String goodIdentificationTypeId = goodIdentification.getString("goodIdentificationTypeId");
                        String idValue = goodIdentification.getString("idValue");
                        doc.add(new StringField("goodIdentificationTypeId", goodIdentificationTypeId, Store.NO));
                        doc.add(new StringField("goodIdentificationIdValue", idValue, Store.NO));
                        doc.add(new StringField(goodIdentificationTypeId + "_GoodIdentification", idValue, Store.NO));
                        this.addTextFieldByWeight(doc, "identificationValue", idValue, "index.weight.GoodIdentification.idValue", 0, false, "fullText");
                    }
                }

                // Virtual ProductIds
                if ("Y".equals(product.getString("isVirtual"))) {
                    if (!"0".equals(UtilProperties.getPropertyValue("prodsearch", "index.weight.Variant.Product.productId", "0"))) {
                        List<GenericValue> variantProductAssocs = delegator.findByAnd("ProductAssoc", UtilMisc.toMap("productId", productId, "productAssocTypeId", "PRODUCT_VARIANT"), null, false);
                        variantProductAssocs = this.filterByThruDate(variantProductAssocs);
                        for (GenericValue variantProductAssoc: variantProductAssocs) {
                            Timestamp fromDate = variantProductAssoc.getTimestamp("fromDate");
                            Timestamp thruDate = variantProductAssoc.getTimestamp("thruDate");
                            if (fromDate != null && fromDate.after(UtilDateTime.nowTimestamp())) {
                                // fromDate is after now, update reindex date but don't index the feature
                                nextReIndex = this.checkSetNextReIndex(fromDate, nextReIndex);
                                continue;
                            } else if (thruDate != null) {
                                nextReIndex = this.checkSetNextReIndex(thruDate, nextReIndex);
                            }
                            this.addTextFieldByWeight(doc, "variantProductId", variantProductAssoc.getString("productIdTo"), "index.weight.Variant.Product.productId", 0, false, "fullText");
                        }
                    }
                }

                // Index product content
                String productContentTypes = UtilProperties.getPropertyValue("prodsearch", "index.include.ProductContentTypes");
                for (String productContentTypeId: productContentTypes.split(",")) {
                    int weight = 1;
                    try {
                        // this is defaulting to a weight of 1 because you specified you wanted to index this type
                        weight = Integer.parseInt(UtilProperties.getPropertyValue("prodsearch", "index.weight.ProductContent." + productContentTypeId, "1"));
                    } catch (Exception e) {
                        Debug.logWarning("Could not parse weight number: " + e.toString(), module);
                    }

                    List<GenericValue> productContentAndInfos = delegator.findByAnd("ProductContentAndInfo", UtilMisc.toMap("productId", productId, "productContentTypeId", productContentTypeId), null, false);
                    productContentAndInfos = this.filterByThruDate(productContentAndInfos);
                    for (GenericValue productContentAndInfo: productContentAndInfos) {
                        Timestamp fromDate = productContentAndInfo.getTimestamp("fromDate");
                        Timestamp thruDate = productContentAndInfo.getTimestamp("thruDate");
                        if (fromDate != null && fromDate.after(UtilDateTime.nowTimestamp())) {
                            // fromDate is after now, update reindex date but don't index the feature
                            nextReIndex = this.checkSetNextReIndex(fromDate, nextReIndex);
                            continue;
                        } else if (thruDate != null) {
                            nextReIndex = this.checkSetNextReIndex(thruDate, nextReIndex);
                        }
                        try {
                            Map<String, Object> drContext = UtilMisc.<String, Object>toMap("product", product);
                            String contentText = DataResourceWorker.renderDataResourceAsText(delegator, productContentAndInfo.getString("dataResourceId"), drContext, null, null, false);
                            this.addTextFieldByWeight(doc, "content", contentText, null, weight, false, "fullText");
                        } catch (IOException e1) {
                            Debug.logError(e1, "Error getting content text to index", module);
                        } catch (GeneralException e1) {
                            Debug.logError(e1, "Error getting content text to index", module);
                        }

                        // TODO: Not indexing alternate locales, needs special handling
                        /*
                        List<GenericValue> alternateViews = productContentAndInfo.getRelated("ContentAssocDataResourceViewTo", UtilMisc.toMap("caContentAssocTypeId", "ALTERNATE_LOCALE"), UtilMisc.toList("-caFromDate"));
                        alternateViews = EntityUtil.filterByDate(alternateViews, UtilDateTime.nowTimestamp(), "caFromDate", "caThruDate", true);
                        for (GenericValue thisView: alternateViews) {
                        }
                        */
                    }
                }

                // Index the product's directProductCategoryIds (direct parents), productCategoryIds (all ancestors) and prodCatalogIds
                this.populateCategoryData(doc, product);

                // Index ProductPrices, uses dynamic fields in the format ${productPriceTypeId}_${productPricePurposeId}_${currencyUomId}_${productStoreGroupId}_price
                List<GenericValue> productPrices = product.getRelated("ProductPrice", null, null, false);
                productPrices = this.filterByThruDate(productPrices);
                for (GenericValue productPrice : productPrices) {
                    Timestamp fromDate = productPrice.getTimestamp("fromDate");
                    Timestamp thruDate = productPrice.getTimestamp("thruDate");
                    if (fromDate != null && fromDate.after(UtilDateTime.nowTimestamp())) {
                        // fromDate is after now, update reindex date but don't index the feature
                        nextReIndex = this.checkSetNextReIndex(fromDate, nextReIndex);
                        continue;
                    } else if (thruDate != null) {
                        nextReIndex = this.checkSetNextReIndex(thruDate, nextReIndex);
                    }
                    StringBuilder fieldNameSb = new StringBuilder();
                    fieldNameSb.append(productPrice.getString("productPriceTypeId"));
                    fieldNameSb.append('_');
                    fieldNameSb.append(productPrice.getString("productPricePurposeId"));
                    fieldNameSb.append('_');
                    fieldNameSb.append(productPrice.getString("currencyUomId"));
                    fieldNameSb.append('_');
                    fieldNameSb.append(productPrice.getString("productStoreGroupId"));
                    fieldNameSb.append("_price");
                    doc.add(new DoubleField(fieldNameSb.toString(), productPrice.getDouble("price"), Store.NO));
                }

                // Index ProductSuppliers
                List<GenericValue> supplierProducts = product.getRelated("SupplierProduct", null, null, false);
                supplierProducts = this.filterByThruDate(supplierProducts, "availableThruDate");
                Set<String> supplierPartyIds = new TreeSet<String>();
                for (GenericValue supplierProduct : supplierProducts) {
                    Timestamp fromDate = supplierProduct.getTimestamp("availableFromDate");
                    Timestamp thruDate = supplierProduct.getTimestamp("availableThruDate");
                    if (fromDate != null && fromDate.after(UtilDateTime.nowTimestamp())) {
                        // fromDate is after now, update reindex date but don't index the feature
                        nextReIndex = this.checkSetNextReIndex(fromDate, nextReIndex);
                        continue;
                    } else if (thruDate != null) {
                        nextReIndex = this.checkSetNextReIndex(thruDate, nextReIndex);
                    }
                    supplierPartyIds.add(supplierProduct.getString("partyId"));
                }
                for (String supplierPartyId : supplierPartyIds) {
                    doc.add(new StringField("supplierPartyId", supplierPartyId, Store.NO));
                }

                // TODO: Add the nextReIndex timestamp to the document for when the product should be automatically re-indexed outside of any ECAs
                // based on the next known from/thru date whose passing will cause a change to the document.  Need to build a scheduled service to look for these.
                return doc;
            }
        } catch (GenericEntityException e) {
            Debug.logError(e, module);
        }
        return null;
    }

    // An attempt to boost/weight values in a similar manner to what OFBiz product search does.
    private void addTextFieldByWeight(Document doc, String fieldName, String value, String property, int defaultWeight, boolean store, String fullTextFieldName) {
        if (fieldName == null) return;

        float weight = 0;
        if (property != null) {
            try {
                weight = Float.parseFloat(UtilProperties.getPropertyValue("prodsearch", property, "0"));
            } catch (Exception e) {
                Debug.logWarning("Could not parse weight number: " + e.toString(), module);
            }
        } else if (defaultWeight > 0) {
            weight = defaultWeight;
        }
        if (weight == 0 && !store) {
            return;
        }
        Field field = new TextField(fieldName, checkValue(value), (store? Store.YES: Store.NO));
        if (weight > 0 && weight != 1) {
            field.setBoost(weight);
        }
        doc.add(field);
        if (fullTextFieldName != null) {
            doc.add(new TextField(fullTextFieldName, checkValue(value), Store.NO));
        }
    }

    private String checkValue(String value) {
        if (UtilValidate.isEmpty(value)) {
            return NULL_STRING;
        }
        return value;
    }

    private Timestamp checkSetNextReIndex(Timestamp nextValue, Timestamp currentValue) {
        // nextValue is null, stick with what we've got
        if (nextValue == null) return currentValue;
        // currentValue is null so use nextValue
        if (currentValue == null) return nextValue;
        // currentValue is after nextValue so use nextValue
        if (currentValue.after(nextValue)) return nextValue;
        // stick with current value
        return currentValue;
    }

    private static final EntityCondition THRU_DATE_ONLY_CONDITION = EntityCondition.makeCondition(
            EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null),
            EntityOperator.OR,
            EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN, UtilDateTime.nowTimestamp())
    );

    private List<GenericValue> filterByThruDate(List<GenericValue> values) {
        return EntityUtil.filterByCondition(values, THRU_DATE_ONLY_CONDITION);
    }

    private List<GenericValue> filterByThruDate(List<GenericValue> values, String thruDateName) {
        return EntityUtil.filterByCondition(values, EntityCondition.makeCondition(
                EntityCondition.makeCondition(thruDateName, EntityOperator.EQUALS, null),
                EntityOperator.OR,
                EntityCondition.makeCondition(thruDateName, EntityOperator.GREATER_THAN, UtilDateTime.nowTimestamp())
        ));
    }

    private Timestamp populateCategoryData(Document doc, GenericValue product) throws GenericEntityException {
        Timestamp nextReIndex = null;
        Set<String> indexedCategoryIds = new TreeSet<String>();
        List<GenericValue> productCategoryMembers = product.getRelated("ProductCategoryMember", null, null, false);
        productCategoryMembers = this.filterByThruDate(productCategoryMembers);

        for (GenericValue productCategoryMember: productCategoryMembers) {
            String productCategoryId = productCategoryMember.getString("productCategoryId");
            doc.add(new StringField("productCategoryId", productCategoryId, Store.NO));
            doc.add(new StringField("directProductCategoryId", productCategoryId, Store.NO));
            indexedCategoryIds.add(productCategoryId);
            Timestamp fromDate = productCategoryMember.getTimestamp("fromDate");
            Timestamp thruDate = productCategoryMember.getTimestamp("thruDate");
            if (fromDate != null && fromDate.after(UtilDateTime.nowTimestamp())) {
                // fromDate is after now, update reindex date but don't index the feature
                nextReIndex = this.checkSetNextReIndex(fromDate, nextReIndex);
                continue;
            } else if (thruDate != null) {
                nextReIndex = this.checkSetNextReIndex(thruDate, nextReIndex);
            }
            nextReIndex = this.checkSetNextReIndex(
                    this.getParentCategories(doc, productCategoryMember.getRelatedOne("ProductCategory", false), indexedCategoryIds),
                    nextReIndex);
        }
        return nextReIndex;
    }

    private Timestamp getParentCategories(Document doc, GenericValue productCategory, Set<String> indexedCategoryIds) throws GenericEntityException {
        return this.getParentCategories(doc, productCategory, indexedCategoryIds, new TreeSet<String>());
    }

    private Timestamp getParentCategories(Document doc, GenericValue productCategory, Set<String> indexedCategoryIds, Set<String> indexedCatalogIds) throws GenericEntityException {
        Timestamp nextReIndex = null;
        nextReIndex = this.getCategoryCatalogs(doc, productCategory, indexedCatalogIds);
        List<GenericValue> productCategoryRollups = productCategory.getRelated("CurrentProductCategoryRollup", null, null, false);
        productCategoryRollups = this.filterByThruDate(productCategoryRollups);
        for (GenericValue productCategoryRollup : productCategoryRollups) {
            Timestamp fromDate = productCategoryRollup.getTimestamp("fromDate");
            Timestamp thruDate = productCategoryRollup.getTimestamp("thruDate");
            if (fromDate != null && fromDate.after(UtilDateTime.nowTimestamp())) {
                // fromDate is after now, update reindex date but don't index now
                nextReIndex = this.checkSetNextReIndex(fromDate, nextReIndex);
                continue;
            } else if (thruDate != null) {
                nextReIndex = this.checkSetNextReIndex(thruDate, nextReIndex);
            }
            // Skip if we've done this category already
            if (!indexedCategoryIds.add(productCategoryRollup.getString("parentProductCategoryId"))) {
                continue;
            }
            GenericValue parentProductCategory = productCategoryRollup.getRelatedOne("ParentProductCategory", false);
            doc.add(new StringField("productCategoryId", parentProductCategory.getString("productCategoryId"), Store.NO));
            nextReIndex = this.checkSetNextReIndex(
                    this.getParentCategories(doc, parentProductCategory, indexedCategoryIds),
                    nextReIndex
            );
        }
        return nextReIndex;
    }

    private Timestamp getCategoryCatalogs(Document doc, GenericValue productCategory, Set<String> indexedCatalogIds) throws GenericEntityException {
        Timestamp nextReIndex = null;
        List<GenericValue> prodCatalogCategories = productCategory.getRelated("ProdCatalogCategory", null, null, false);
        prodCatalogCategories = this.filterByThruDate(prodCatalogCategories);
        for (GenericValue prodCatalogCategory : prodCatalogCategories) {
            Timestamp fromDate = prodCatalogCategory.getTimestamp("fromDate");
            Timestamp thruDate = prodCatalogCategory.getTimestamp("thruDate");
            if (fromDate != null && fromDate.after(UtilDateTime.nowTimestamp())) {
                // fromDate is after now, update reindex date but don't index now
                nextReIndex = this.checkSetNextReIndex(fromDate, nextReIndex);
                continue;
            } else if (thruDate != null) {
                nextReIndex = this.checkSetNextReIndex(thruDate, nextReIndex);
            }
            // Skip if we've done this catalog already
            if (!indexedCatalogIds.add(prodCatalogCategory.getString("prodCatalogId"))) {
                continue;
            }
            doc.add(new StringField("prodCatalogId", prodCatalogCategory.getString("prodCatalogId"), Store.NO));
        }
        return nextReIndex;
    }

    private long quantizeTimestampToDays(Timestamp date) {
        long quantizedDate = 0;
        if (date != null) {
            quantizedDate = date.getTime()/24/3600;
        }
        return quantizedDate;
    }
}
