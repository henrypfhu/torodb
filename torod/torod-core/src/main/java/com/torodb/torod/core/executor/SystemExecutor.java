/*
 *     This file is part of ToroDB.
 *
 *     ToroDB is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Affero General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     ToroDB is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Affero General Public License for more details.
 *
 *     You should have received a copy of the GNU Affero General Public License
 *     along with ToroDB. If not, see <http://www.gnu.org/licenses/>.
 *
 *     Copyright (c) 2014, 8Kdata Technology
 *     
 */

package com.torodb.torod.core.executor;

import com.torodb.torod.core.pojos.NamedToroIndex;
import com.torodb.torod.core.pojos.IndexedAttributes;
import com.torodb.torod.core.subdocument.SubDocType;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.Future;
import javax.annotation.Nonnegative;
import javax.annotation.Nullable;

/**
 *
 */
public interface SystemExecutor {

    Future<?> createCollection(
            String collection,
            @Nullable CreateCollectionCallback callback)
            throws ToroTaskExecutionException;

    Future<?> createSubDocTable(
            String collection,
            SubDocType type,
            @Nullable CreateSubDocTypeTableCallback callback)
            throws ToroTaskExecutionException;

    Future<?> reserveDocIds(
            String collection,
            @Nonnegative int idsToReserve,
            @Nullable ReserveDocIdsCallback callback)
            throws ToroTaskExecutionException;

    Future<Map<String, Integer>> findCollections();

    /**
     * Returns the last reserved document id for each collection in the database.
     * <p>
     * @return A {@link Future} whose result is a map that stores the last used doc id for each collection in the
     *         database
     * @throws com.torodb.torod.core.executor.ToroTaskExecutionException
     */
    Future<Map<String, Integer>> getLastUsedIds()
            throws ToroTaskExecutionException;

    /**
     * Returns a number that identifies the last pending job.
     * <p>
     * Subsequent calls to this method will return the same or a higher value, but never a lower one.
     * <p>
     * This number can be used as input to {@link UserExecutor#pauseUntil(int) }
     * <p>
     * @return
     * @see UserExecutor#pauseUntil(int)
     */
    long getTick();
    
    Future<NamedToroIndex> createIndex(
            String collectionName,
            String indexName, 
            IndexedAttributes attributes,
            boolean unique,
            boolean blocking,
            CreateIndexCallback callback
    );
    
    Future<Boolean> dropIndex(
            String indexName
    );
    
    public Future<Collection<? extends NamedToroIndex>> getIndexes();

    public static interface CreateCollectionCallback {

        public void createdCollection(String collection);
    }

    public static interface CreateSubDocTypeTableCallback {

        public void createSubDocTypeTable(String colection, SubDocType type);
    }

    public static interface ReserveDocIdsCallback {

        public void reservedDocIds(String collection, @Nonnegative int idsToReserve);
    }
    
    public static interface CreateIndexCallback {
        
        public void createdIndex(NamedToroIndex index);
    }
}