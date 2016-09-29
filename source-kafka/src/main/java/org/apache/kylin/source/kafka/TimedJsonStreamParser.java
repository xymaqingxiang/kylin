/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kylin.source.kafka;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.DeserializationFeature;
import org.apache.commons.lang3.StringUtils;
import org.apache.kylin.common.util.StreamingMessage;
import org.apache.kylin.metadata.model.TblColRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import com.fasterxml.jackson.databind.type.SimpleType;
import com.google.common.collect.Lists;

/**
 * An utility class which parses a JSON streaming message to a list of strings (represent a row in table).
 *
 * Each message should have a property whose value represents the message's timestamp, default the column name is "timestamp"
 * but can be customized by StreamingParser#PROPERTY_TS_PARSER.
 *
 * By default it will parse the timestamp col value as Unix time. If the format isn't Unix time, need specify the time parser
 * with property StreamingParser#PROPERTY_TS_PARSER.
 *
 * It also support embedded JSON format; Use a separator (customized by StreamingParser#EMBEDDED_PROPERTY_SEPARATOR) to concat
 * the property names.
 *
 */
public final class TimedJsonStreamParser extends StreamingParser {

    private static final Logger logger = LoggerFactory.getLogger(TimedJsonStreamParser.class);

    private List<TblColRef> allColumns;
    private final ObjectMapper mapper;
    private String tsColName = null;
    private String tsParser = null;
    private String separator = null;

    private final JavaType mapType = MapType.construct(HashMap.class, SimpleType.construct(String.class), SimpleType.construct(Object.class));

    private AbstractTimeParser streamTimeParser;

    public TimedJsonStreamParser(List<TblColRef> allColumns, Map<String, String> properties) {
        this.allColumns = allColumns;
        if (properties == null) {
            properties = StreamingParser.defaultProperties;
        }

        tsColName = properties.get(PROPERTY_TS_COLUMN_NAME);
        tsParser = properties.get(PROPERTY_TS_PARSER);
        separator = properties.get(EMBEDDED_PROPERTY_SEPARATOR);

        if (!StringUtils.isEmpty(tsParser)) {
            try {
                Class clazz = Class.forName(tsParser);
                Constructor constructor = clazz.getConstructor(Map.class);
                streamTimeParser = (AbstractTimeParser) constructor.newInstance(properties);
            } catch (Exception e) {
                throw new IllegalStateException("Invalid StreamingConfig, tsParser " + tsParser + ", parserProperties " + properties + ".", e);
            }
        } else {
            throw new IllegalStateException("Invalid StreamingConfig, tsParser " + tsParser + ", parserProperties " + properties + ".");
        }
        mapper = new ObjectMapper();
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.disable(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE);
        mapper.enable(DeserializationFeature.USE_JAVA_ARRAY_FOR_JSON_ARRAY);
    }

    @Override
    public StreamingMessage parse(ByteBuffer buffer) {
        try {
            Map<String, Object> message = mapper.readValue(new ByteBufferBackedInputStream(buffer), mapType);
            Map<String, Object> root = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            root.putAll(message);
            String tsStr = objToString(root.get(tsColName));
            long t = streamTimeParser.parseTime(tsStr);
            ArrayList<String> result = Lists.newArrayList();

            for (TblColRef column : allColumns) {
                String columnName = column.getName().toLowerCase();

                if (populateDerivedTimeColumns(columnName, result, t) == false) {
                    result.add(getValueByKey(columnName, root));
                }
            }

            return new StreamingMessage(result, 0, t, Collections.<String, Object> emptyMap());
        } catch (IOException e) {
            logger.error("error", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean filter(StreamingMessage streamingMessage) {
        return true;
    }

    protected String getValueByKey(String key, Map<String, Object> root) throws IOException {
        if (root.containsKey(key)) {
            return objToString(root.get(key));
        }

        if (key.contains(separator)) {
            String[] names = key.toLowerCase().split(separator);
            Map<String, Object> tempMap = null;
            for (int i = 0; i < names.length - 1; i++) {
                Object o = root.get(names[i]);
                if (o instanceof Map) {
                    tempMap = (Map<String, Object>) o;
                } else {
                    throw new IOException("Property '" + names[i] + "' is not embedded format");
                }
            }
            Object finalObject = tempMap.get(names[names.length - 1]);
            return objToString(finalObject);

        }

        return StringUtils.EMPTY;
    }

    static String objToString(Object value) {
        if (value == null)
            return StringUtils.EMPTY;

        return String.valueOf(value);
    }

}
