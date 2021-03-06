/*
 * Copyright 2009-2013 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.uci.ics.asterix.metadata.declared;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.uci.ics.asterix.om.types.IAType;
import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.common.utils.ListSet;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.LogicalVariable;
import edu.uci.ics.hyracks.algebricks.core.algebra.metadata.IDataSource;
import edu.uci.ics.hyracks.algebricks.core.algebra.metadata.IDataSourcePropertiesProvider;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.OrderOperator.IOrder.OrderKind;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.FunctionalDependency;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.ILocalStructuralProperty;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.INodeDomain;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.IPartitioningProperty;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.IPhysicalPropertiesVector;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.LocalOrderProperty;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.OrderColumn;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.RandomPartitioningProperty;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.StructuralPropertiesVector;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.UnorderedPartitionedProperty;

public abstract class AqlDataSource implements IDataSource<AqlSourceId> {

    private AqlSourceId id;
    private String datasourceDataverse;
    private String datasourceName;
    private AqlDataSourceType datasourceType;
    protected IAType[] schemaTypes;
    protected INodeDomain domain;
    private Map<String, Serializable> properties = new HashMap<String, Serializable>();

    public enum AqlDataSourceType {
        INTERNAL_DATASET,
        EXTERNAL_DATASET,
        FEED
    }

    public AqlDataSource(AqlSourceId id, String datasourceDataverse, String datasourceName, IAType itemType,
            AqlDataSourceType datasourceType) throws AlgebricksException {
        this.id = id;
        this.datasourceDataverse = datasourceDataverse;
        this.datasourceName = datasourceName;
        this.datasourceType = datasourceType;
    }

    public String getDatasourceDataverse() {
        return datasourceDataverse;
    }

    public String getDatasourceName() {
        return datasourceName;
    }

    public abstract IAType[] getSchemaTypes();

    public abstract INodeDomain getDomain();

    @Override
    public AqlSourceId getId() {
        return id;
    }

    @Override
    public String toString() {
        return id.toString();
    }

    @Override
    public IDataSourcePropertiesProvider getPropertiesProvider() {
        return new AqlDataSourcePartitioningProvider(datasourceType, domain);
    }

    @Override
    public void computeFDs(List<LogicalVariable> scanVariables, List<FunctionalDependency> fdList) {
        int n = scanVariables.size();
        if (n > 1) {
            List<LogicalVariable> head = new ArrayList<LogicalVariable>(scanVariables.subList(0, n - 1));
            List<LogicalVariable> tail = new ArrayList<LogicalVariable>(1);
            tail.addAll(scanVariables);
            FunctionalDependency fd = new FunctionalDependency(head, tail);
            fdList.add(fd);
        }
    }

    private static class AqlDataSourcePartitioningProvider implements IDataSourcePropertiesProvider {

        private INodeDomain domain;

        private AqlDataSourceType aqlDataSourceType;

        public AqlDataSourcePartitioningProvider(AqlDataSourceType datasetSourceType, INodeDomain domain) {
            this.aqlDataSourceType = datasetSourceType;
            this.domain = domain;
        }

        @Override
        public IPhysicalPropertiesVector computePropertiesVector(List<LogicalVariable> scanVariables) {
            IPhysicalPropertiesVector propsVector = null;

            switch (aqlDataSourceType) {
                case EXTERNAL_DATASET: {
                    IPartitioningProperty pp = new RandomPartitioningProperty(domain);
                    List<ILocalStructuralProperty> propsLocal = new ArrayList<ILocalStructuralProperty>();
                    propsVector = new StructuralPropertiesVector(pp, propsLocal);
                }

                case FEED: {
                    int n = scanVariables.size();
                    IPartitioningProperty pp;
                    if (n < 2) {
                        pp = new RandomPartitioningProperty(domain);
                    } else {
                        Set<LogicalVariable> pvars = new ListSet<LogicalVariable>();
                        int i = 0;
                        for (LogicalVariable v : scanVariables) {
                            pvars.add(v);
                            ++i;
                            if (i >= n - 1) {
                                break;
                            }
                        }
                        pp = new UnorderedPartitionedProperty(pvars, domain);
                    }
                    List<ILocalStructuralProperty> propsLocal = new ArrayList<ILocalStructuralProperty>();
                    propsVector = new StructuralPropertiesVector(pp, propsLocal);
                    break;
                }

                case INTERNAL_DATASET: {
                    int n = scanVariables.size();
                    IPartitioningProperty pp;
                    if (n < 2) {
                        pp = new RandomPartitioningProperty(domain);
                    } else {
                        Set<LogicalVariable> pvars = new ListSet<LogicalVariable>();
                        int i = 0;
                        for (LogicalVariable v : scanVariables) {
                            pvars.add(v);
                            ++i;
                            if (i >= n - 1) {
                                break;
                            }
                        }
                        pp = new UnorderedPartitionedProperty(pvars, domain);
                    }
                    List<ILocalStructuralProperty> propsLocal = new ArrayList<ILocalStructuralProperty>();
                    for (int i = 0; i < n - 1; i++) {
                        propsLocal.add(new LocalOrderProperty(new OrderColumn(scanVariables.get(i), OrderKind.ASC)));
                    }
                    propsVector = new StructuralPropertiesVector(pp, propsLocal);
                }
                    break;

                default: {
                    throw new IllegalArgumentException();
                }
            }
            return propsVector;
        }

    }

    public AqlDataSourceType getDatasourceType() {
        return datasourceType;
    }

    public Map<String, Serializable> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Serializable> properties) {
        this.properties = properties;
    }

}
