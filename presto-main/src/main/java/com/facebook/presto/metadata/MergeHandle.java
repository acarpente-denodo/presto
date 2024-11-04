/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.metadata;

import com.facebook.presto.spi.ConnectorMergeHandle;
import com.facebook.presto.spi.TableHandle;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

import static java.util.Objects.requireNonNull;

public final class MergeHandle
{
    private final TableHandle tableHandle;
    private final ConnectorMergeHandle connectorMergeHandle;

    @JsonCreator
    public MergeHandle(
            @JsonProperty("tableHandle") TableHandle tableHandle,
            @JsonProperty("connectorMergeHandle") ConnectorMergeHandle connectorMergeHandle)
    {
        this.tableHandle = requireNonNull(tableHandle, "tableHandle is null");
        this.connectorMergeHandle = requireNonNull(connectorMergeHandle, "connectorMergeHandle is null");
    }

    @JsonProperty
    public TableHandle getTableHandle()
    {
        return tableHandle;
    }

    @JsonProperty
    public ConnectorMergeHandle getConnectorMergeHandle()
    {
        return connectorMergeHandle;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(tableHandle, connectorMergeHandle);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        MergeHandle o = (MergeHandle) obj;
        return Objects.equals(this.tableHandle, o.tableHandle) &&
                Objects.equals(this.connectorMergeHandle, o.connectorMergeHandle);
    }

    @Override
    public String toString()
    {
        return tableHandle + ":" + connectorMergeHandle;
    }
}
