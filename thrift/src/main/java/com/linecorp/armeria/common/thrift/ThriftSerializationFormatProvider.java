/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common.thrift;

import static com.google.common.net.MediaType.create;

import java.util.Set;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SerializationFormatProvider;

/**
 * {@link SerializationFormatProvider} that provides the Thrift-related {@link SerializationFormat}s.
 */
public final class ThriftSerializationFormatProvider extends SerializationFormatProvider {
    @Override
    protected Set<Entry> entries() {
        return ImmutableSet.of(
                new Entry("tbinary",
                          create("application", "x-thrift").withParameter("protocol", "TBINARY"),
                          create("application", "vnd.apache.thrift.binary")),
                new Entry("tcompact",
                          create("application", "x-thrift").withParameter("protocol", "TCOMPACT"),
                          create("application", "vnd.apache.thrift.compact")),
                new Entry("tjson",
                          create("application", "x-thrift").withParameter("protocol", "TJSON"),
                          create("application", "vnd.apache.thrift.json")),
                new Entry("ttext",
                          create("application", "x-thrift").withParameter("protocol", "TTEXT"),
                          create("application", "vnd.apache.thrift.text")));
    }
}
