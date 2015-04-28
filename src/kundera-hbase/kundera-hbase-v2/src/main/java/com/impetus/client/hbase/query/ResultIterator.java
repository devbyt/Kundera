/*******************************************************************************
 * * Copyright 2015 Impetus Infotech.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 ******************************************************************************/
package com.impetus.client.hbase.query;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.apache.hadoop.hbase.filter.FilterList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.impetus.client.hbase.HBaseClient;
import com.impetus.client.hbase.admin.HBaseDataHandler;
import com.impetus.client.hbase.query.HBaseQuery.QueryTranslator;
import com.impetus.client.hbase.utils.HBaseUtils;
import com.impetus.kundera.KunderaException;
import com.impetus.kundera.client.Client;
import com.impetus.kundera.client.EnhanceEntity;
import com.impetus.kundera.metadata.model.EntityMetadata;
import com.impetus.kundera.persistence.PersistenceDelegator;
import com.impetus.kundera.property.PropertyAccessorHelper;
import com.impetus.kundera.query.IResultIterator;

/**
 * The Class ResultIterator.
 * 
 * @author Pragalbh Garg
 * @param <E>
 *            the element type
 */
class ResultIterator<E> implements IResultIterator<E>
{

    /** The client. */
    private HBaseClient client;

    /** The entity metadata. */
    private EntityMetadata entityMetadata;

    /** The persistence delegator. */
    private PersistenceDelegator persistenceDelegator;

    /** The handler. */
    private HBaseDataHandler handler;

    /** The translator. */
    private QueryTranslator translator;

    /** The columns. */
    private List<Map<String, Object>> columns;

    /** The fetch size. */
    private int fetchSize;

    /** The count. */
    private int count;

    /** The scroll complete. */
    private boolean scrollComplete;

    /** the log used by this class. */
    private static Logger log = LoggerFactory.getLogger(ResultIterator.class);

    /**
     * Instantiates a new result iterator.
     * 
     * @param client
     *            the client
     * @param m
     *            the entity metadata
     * @param pd
     *            the pd
     * @param fetchSize
     *            the fetch size
     * @param translator
     *            the translator
     * @param columns
     *            the columns
     */
    public ResultIterator(HBaseClient client, EntityMetadata m, PersistenceDelegator pd, int fetchSize,
            QueryTranslator translator, List<Map<String, Object>> columns)
    {
        this.entityMetadata = m;
        this.client = client;
        this.persistenceDelegator = pd;
        this.handler = client.getHandle();
        this.handler.setFetchSize(fetchSize);
        this.fetchSize = fetchSize;
        this.translator = translator;
        this.columns = columns;
        onQuery(m, client);
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.util.Iterator#hasNext()
     */
    @Override
    public boolean hasNext()
    {
        boolean available = handler.hasNext();
        if (!available || fetchSize == 0)
        {
            scrollComplete = true;
            handler.reset();
            return false;
        }
        return available;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.util.Iterator#next()
     */
    @Override
    public E next()
    {
        if (!checkOnFetchSize() || scrollComplete)
        {
            throw new NoSuchElementException("Nothing to scroll further for:" + entityMetadata.getEntityClazz());
        }
        E result = (E) handler.next(entityMetadata, columns);
        if (!entityMetadata.isRelationViaJoinTable()
                && (entityMetadata.getRelationNames() == null || (entityMetadata.getRelationNames().isEmpty())))
        {
            return result;
        }
        else
        {
            return result = setRelationEntities(result, client, entityMetadata);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.util.Iterator#remove()
     */
    @Override
    public void remove()
    {
        throw new UnsupportedOperationException("remove() over result iterator is not supported");
    }

    /**
     * Sets the relation entities.
     * 
     * @param enhanceEntity
     *            the enhance entity
     * @param client
     *            the client
     * @param m
     *            the m
     * @return the e
     */
    private E setRelationEntities(Object enhanceEntity, Client client, EntityMetadata m)
    {
        E result = null;
        if (enhanceEntity != null)
        {
            if (!(enhanceEntity instanceof EnhanceEntity))
            {
                enhanceEntity = new EnhanceEntity(enhanceEntity, PropertyAccessorHelper.getId(enhanceEntity, m), null);
            }

            EnhanceEntity ee = (EnhanceEntity) enhanceEntity;

            result = (E) client.getReader().recursivelyFindEntities(ee.getEntity(), ee.getRelations(), m,
                    persistenceDelegator, false, new HashMap<Object, Object>());
        }
        return result;
    }

    /**
     * On query.
     * 
     * @param m
     *            the m
     * @param client
     *            the client
     */
    private void onQuery(EntityMetadata m, Client client)
    {
        String tableName = HBaseUtils.getHTableName(m.getSchema(), m.getTableName());
        try
        {
            handler.readData(tableName, m, null, translator.getStartRow(), translator.getEndRow(), getColumnsToOuput(),
                    (FilterList) translator.getFilters());
        }
        catch (IOException e)
        {
            log.error(e.getMessage());
            throw new KunderaException("Error in connecting to database or some network problem. Caused by: ", e);
        }
    }

    /**
     * Gets the col as list.
     * 
     * @return the col as list
     */
    private List<Map<String, Object>> getColumnsToOuput()
    {
        return this.columns;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.impetus.kundera.query.IResultIterator#next(int)
     */
    @Override
    public List<E> next(int chunkSize)
    {
        int counter = 0;
        List<E> chunkList = new ArrayList<E>();
        while (this.hasNext() && counter++ < chunkSize)
        {
            chunkList.add(this.next());
        }
        return chunkList;
    }

    /**
     * Check on fetch size.
     * 
     * @return true, if successful
     */
    private boolean checkOnFetchSize()
    {
        if (count++ < fetchSize)
        {
            return true;
        }
        count = 0;
        scrollComplete = true;
        return false;
    }
}