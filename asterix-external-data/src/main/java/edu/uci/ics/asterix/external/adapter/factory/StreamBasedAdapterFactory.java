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
package edu.uci.ics.asterix.external.adapter.factory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import edu.uci.ics.asterix.common.exceptions.AsterixException;
import edu.uci.ics.asterix.external.util.INodeResolver;
import edu.uci.ics.asterix.metadata.feeds.ConditionalPushTupleParserFactory;
import edu.uci.ics.asterix.metadata.feeds.IAdapterFactory;
import edu.uci.ics.asterix.om.types.ARecordType;
import edu.uci.ics.asterix.om.types.ATypeTag;
import edu.uci.ics.asterix.om.types.AUnionType;
import edu.uci.ics.asterix.om.types.IAType;
import edu.uci.ics.asterix.runtime.operators.file.AdmSchemafullRecordParserFactory;
import edu.uci.ics.asterix.runtime.operators.file.NtDelimitedDataTupleParserFactory;
import edu.uci.ics.hyracks.algebricks.common.exceptions.NotImplementedException;
import edu.uci.ics.hyracks.dataflow.common.data.parsers.DoubleParserFactory;
import edu.uci.ics.hyracks.dataflow.common.data.parsers.FloatParserFactory;
import edu.uci.ics.hyracks.dataflow.common.data.parsers.IValueParserFactory;
import edu.uci.ics.hyracks.dataflow.common.data.parsers.IntegerParserFactory;
import edu.uci.ics.hyracks.dataflow.common.data.parsers.LongParserFactory;
import edu.uci.ics.hyracks.dataflow.common.data.parsers.UTF8StringParserFactory;
import edu.uci.ics.hyracks.dataflow.std.file.ITupleParser;
import edu.uci.ics.hyracks.dataflow.std.file.ITupleParserFactory;

public abstract class StreamBasedAdapterFactory implements IAdapterFactory {

    private static final long serialVersionUID = 1L;
    protected static final Logger LOGGER = Logger.getLogger(StreamBasedAdapterFactory.class.getName());

    protected Map<String, String> configuration;
    protected static INodeResolver nodeResolver;

    public static final String KEY_FORMAT = "format";
    public static final String KEY_PARSER_FACTORY = "parser";
    public static final String KEY_DELIMITER = "delimiter";
    public static final String KEY_PATH = "path";
    public static final String KEY_SOURCE_DATATYPE = "output-type-name";
    public static final String FORMAT_DELIMITED_TEXT = "delimited-text";
    public static final String FORMAT_ADM = "adm";
    public static final String NODE_RESOLVER_FACTORY_PROPERTY = "node.Resolver";
    public static final String BATCH_SIZE = "batch-size";
    public static final String BATCH_INTERVAL = "batch-interval";

    protected ITupleParserFactory parserFactory;
    protected ITupleParser parser;

    protected static final HashMap<ATypeTag, IValueParserFactory> typeToValueParserFactMap = new HashMap<ATypeTag, IValueParserFactory>();
    static {
        typeToValueParserFactMap.put(ATypeTag.INT32, IntegerParserFactory.INSTANCE);
        typeToValueParserFactMap.put(ATypeTag.FLOAT, FloatParserFactory.INSTANCE);
        typeToValueParserFactMap.put(ATypeTag.DOUBLE, DoubleParserFactory.INSTANCE);
        typeToValueParserFactMap.put(ATypeTag.INT64, LongParserFactory.INSTANCE);
        typeToValueParserFactMap.put(ATypeTag.STRING, UTF8StringParserFactory.INSTANCE);
    }

    protected ITupleParserFactory getDelimitedDataTupleParserFactory(ARecordType recordType, boolean conditionalPush)
            throws AsterixException {
        int n = recordType.getFieldTypes().length;
        IValueParserFactory[] fieldParserFactories = new IValueParserFactory[n];
        for (int i = 0; i < n; i++) {
            ATypeTag tag = null;
            if (recordType.getFieldTypes()[i].getTypeTag() == ATypeTag.UNION) {
                List<IAType> unionTypes = ((AUnionType) recordType.getFieldTypes()[i]).getUnionList();
                if (unionTypes.size() != 2 && unionTypes.get(0).getTypeTag() != ATypeTag.NULL) {
                    throw new NotImplementedException("Non-optional UNION type is not supported.");
                }
                tag = unionTypes.get(1).getTypeTag();
            } else {
                tag = recordType.getFieldTypes()[i].getTypeTag();
            }
            if (tag == null) {
                throw new NotImplementedException("Failed to get the type information for field " + i + ".");
            }
            IValueParserFactory vpf = typeToValueParserFactMap.get(tag);
            if (vpf == null) {
                throw new NotImplementedException("No value parser factory for delimited fields of type " + tag);
            }
            fieldParserFactories[i] = vpf;
        }
        String delimiterValue = (String) configuration.get(KEY_DELIMITER);
        if (delimiterValue != null && delimiterValue.length() > 1) {
            throw new AsterixException("improper delimiter");
        }

        Character delimiter = delimiterValue.charAt(0);

        return conditionalPush ? new ConditionalPushTupleParserFactory(recordType, fieldParserFactories, delimiter,
                configuration) : new NtDelimitedDataTupleParserFactory(recordType, fieldParserFactories, delimiter);
    }

    protected ITupleParserFactory getADMDataTupleParserFactory(ARecordType recordType, boolean conditionalPush)
            throws AsterixException {
        try {
            return conditionalPush ? new ConditionalPushTupleParserFactory(recordType, configuration)
                    : new AdmSchemafullRecordParserFactory(recordType);
        } catch (Exception e) {
            throw new AsterixException(e);
        }

    }

    protected void configureFormat(IAType sourceDatatype) throws Exception {
        String propValue = (String) configuration.get(BATCH_SIZE);
        int batchSize = propValue != null ? Integer.parseInt(propValue) : -1;
        propValue = (String) configuration.get(BATCH_INTERVAL);
        long batchInterval = propValue != null ? Long.parseLong(propValue) : -1;
        boolean conditionalPush = batchSize > 0 || batchInterval > 0;

        String parserFactoryClassname = (String) configuration.get(KEY_PARSER_FACTORY);
        if (parserFactoryClassname == null) {
            String specifiedFormat = (String) configuration.get(KEY_FORMAT);
            if (specifiedFormat == null) {
                throw new IllegalArgumentException(" Unspecified data format");
            } else if (FORMAT_DELIMITED_TEXT.equalsIgnoreCase(specifiedFormat)) {
                parserFactory = getDelimitedDataTupleParserFactory((ARecordType) sourceDatatype, conditionalPush);
            } else if (FORMAT_ADM.equalsIgnoreCase((String) configuration.get(KEY_FORMAT))) {
                parserFactory = getADMDataTupleParserFactory((ARecordType) sourceDatatype, conditionalPush);
            } else {
                throw new IllegalArgumentException(" format " + configuration.get(KEY_FORMAT) + " not supported");
            }
        } else {
            parserFactory = (ITupleParserFactory) Class.forName(parserFactoryClassname).newInstance();
        }

    }

}
