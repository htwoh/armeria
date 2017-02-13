/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.thrift;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.apache.thrift.AsyncProcessFunction;
import org.apache.thrift.ProcessFunction;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.meta_data.FieldMetaData;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.transport.TMemoryBuffer;
import org.apache.thrift.transport.TMemoryInputTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.MediaType;

import com.linecorp.armeria.common.DefaultRpcResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.HttpData;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.common.thrift.ThriftReply;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.thrift.ThriftFieldAccess;
import com.linecorp.armeria.internal.thrift.ThriftFunction;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.http.AbstractHttpService;

/**
 * A {@link Service} that handles a Thrift call.
 *
 * @see ThriftProtocolFactories
 */
public class THttpService extends AbstractHttpService {

    private static final Logger logger = LoggerFactory.getLogger(THttpService.class);

    private static final String PROTOCOL_NOT_SUPPORTED = "Specified content-type not supported";

    private static final String ACCEPT_THRIFT_PROTOCOL_MUST_MATCH_CONTENT_TYPE =
            "Thrift protocol specified in Accept header must match " +
            "the one specified in the content-type header";

    private static final Map<SerializationFormat, ThreadLocalTProtocol> FORMAT_TO_THREAD_LOCAL_INPUT_PROTOCOL =
            createFormatToThreadLocalTProtocolMap();

    /**
     * Creates a new {@link THttpService} with the specified service implementation, supporting all thrift
     * protocols and defaulting to {@link ThriftSerializationFormats#BINARY TBinary} protocol when the client
     * doesn't specify one.
     *
     * <p>Currently, the only way to specify a serialization format is by using the HTTP session
     * protocol and setting the Content-Type header to the appropriate {@link SerializationFormat#mediaType()}.
     *
     * @param implementation an implementation of {@code *.Iface} or {@code *.AsyncIface} service interface
     *                       generated by the Apache Thrift compiler
     */
    public static THttpService of(Object implementation) {
        return of(implementation, ThriftSerializationFormats.BINARY);
    }

    /**
     * Creates a new multiplexed {@link THttpService} with the specified service implementations, supporting
     * all thrift protocols and defaulting to {@link ThriftSerializationFormats#BINARY TBinary} protocol when
     * the client doesn't specify one.
     *
     * <p>Currently, the only way to specify a serialization format is by using the HTTP session
     * protocol and setting the Content-Type header to the appropriate {@link SerializationFormat#mediaType()}.
     *
     * @param implementations a {@link Map} whose key is service name and value is the implementation of
     *                        {@code *.Iface} or {@code *.AsyncIface} service interface generated by
     *                        the Apache Thrift compiler
     */
    public static THttpService of(Map<String, ?> implementations) {
        return of(implementations, ThriftSerializationFormats.BINARY);
    }

    /**
     * Creates a new {@link THttpService} with the specified service implementation, supporting all thrift
     * protocols and defaulting to the specified {@code defaultSerializationFormat} when the client doesn't
     * specify one.
     *
     * <p>Currently, the only way to specify a serialization format is by using the HTTP session
     * protocol and setting the Content-Type header to the appropriate {@link SerializationFormat#mediaType()}.
     *
     *
     * @param implementation an implementation of {@code *.Iface} or {@code *.AsyncIface} service interface
     *                       generated by the Apache Thrift compiler
     * @param defaultSerializationFormat the default serialization format to use when not specified by the
     *                                   client
     */
    public static THttpService of(Object implementation,
                                  SerializationFormat defaultSerializationFormat) {

        return new THttpService(ThriftCallService.of(implementation),
                                defaultSerializationFormat, ThriftSerializationFormats.values());
    }

    /**
     * Creates a new multiplexed {@link THttpService} with the specified service implementations, supporting
     * all thrift protocols and defaulting to the specified {@code defaultSerializationFormat} when the client
     * doesn't specify one.
     *
     * <p>Currently, the only way to specify a serialization format is by using the HTTP session
     * protocol and setting the Content-Type header to the appropriate {@link SerializationFormat#mediaType()}.
     *
     *
     * @param implementations a {@link Map} whose key is service name and value is the implementation of
     *                        {@code *.Iface} or {@code *.AsyncIface} service interface generated by
     *                        the Apache Thrift compiler
     * @param defaultSerializationFormat the default serialization format to use when not specified by the
     *                                   client
     */
    public static THttpService of(Map<String, ?> implementations,
                                  SerializationFormat defaultSerializationFormat) {

        return new THttpService(ThriftCallService.of(implementations),
                                defaultSerializationFormat, ThriftSerializationFormats.values());
    }

    /**
     * Creates a new {@link THttpService} with the specified service implementation, supporting only the
     * formats specified and defaulting to the specified {@code defaultSerializationFormat} when the client
     * doesn't specify one.
     *
     * <p>Currently, the only way to specify a serialization format is by using the HTTP session protocol and
     * setting the Content-Type header to the appropriate {@link SerializationFormat#mediaType()}.
     *
     * @param implementation an implementation of {@code *.Iface} or {@code *.AsyncIface} service interface
     *                       generated by the Apache Thrift compiler
     * @param defaultSerializationFormat the default serialization format to use when not specified by the
     *                                   client
     * @param otherAllowedSerializationFormats other serialization formats that should be supported by this
     *                                         service in addition to the default
     */
    public static THttpService ofFormats(
            Object implementation,
            SerializationFormat defaultSerializationFormat,
            SerializationFormat... otherAllowedSerializationFormats) {

        requireNonNull(otherAllowedSerializationFormats, "otherAllowedSerializationFormats");
        return ofFormats(implementation,
                         defaultSerializationFormat,
                         Arrays.asList(otherAllowedSerializationFormats));
    }

    /**
     * Creates a new multiplexed {@link THttpService} with the specified service implementations, supporting
     * only the formats specified and defaulting to the specified {@code defaultSerializationFormat} when the
     * client doesn't specify one.
     *
     * <p>Currently, the only way to specify a serialization format is by using the HTTP session protocol and
     * setting the Content-Type header to the appropriate {@link SerializationFormat#mediaType()}.
     *
     * @param implementations a {@link Map} whose key is service name and value is the implementation of
     *                        {@code *.Iface} or {@code *.AsyncIface} service interface generated by
     *                        the Apache Thrift compiler
     * @param defaultSerializationFormat the default serialization format to use when not specified by the
     *                                   client
     * @param otherAllowedSerializationFormats other serialization formats that should be supported by this
     *                                         service in addition to the default
     */
    public static THttpService ofFormats(
            Map<String, ?> implementations,
            SerializationFormat defaultSerializationFormat,
            SerializationFormat... otherAllowedSerializationFormats) {

        requireNonNull(otherAllowedSerializationFormats, "otherAllowedSerializationFormats");
        return ofFormats(implementations,
                         defaultSerializationFormat,
                         Arrays.asList(otherAllowedSerializationFormats));
    }

    /**
     * Creates a new {@link THttpService} with the specified service implementation, supporting the protocols
     * specified in {@code allowedSerializationFormats} and defaulting to the specified
     * {@code defaultSerializationFormat} when the client doesn't specify one.
     *
     * <p>Currently, the only way to specify a serialization format is by using the HTTP session protocol and
     * setting the Content-Type header to the appropriate {@link SerializationFormat#mediaType()}.
     *
     * @param implementation an implementation of {@code *.Iface} or {@code *.AsyncIface} service interface
     *                       generated by the Apache Thrift compiler
     * @param defaultSerializationFormat the default serialization format to use when not specified by the
     *                                   client
     * @param otherAllowedSerializationFormats other serialization formats that should be supported by this
     *                                         service in addition to the default
     */
    public static THttpService ofFormats(
            Object implementation,
            SerializationFormat defaultSerializationFormat,
            Iterable<SerializationFormat> otherAllowedSerializationFormats) {

        requireNonNull(otherAllowedSerializationFormats, "otherAllowedSerializationFormats");

        final Set<SerializationFormat> allowedSerializationFormatsSet =
                newAllowedSerializationFormats(defaultSerializationFormat, otherAllowedSerializationFormats);

        return new THttpService(ThriftCallService.of(implementation),
                                defaultSerializationFormat, allowedSerializationFormatsSet);
    }

    /**
     * Creates a new multiplexed {@link THttpService} with the specified service implementations, supporting
     * the protocols specified in {@code allowedSerializationFormats} and defaulting to the specified
     * {@code defaultSerializationFormat} when the client doesn't specify one.
     *
     * <p>Currently, the only way to specify a serialization format is by using the HTTP session protocol and
     * setting the Content-Type header to the appropriate {@link SerializationFormat#mediaType()}.
     *
     * @param implementations a {@link Map} whose key is service name and value is the implementation of
     *                        {@code *.Iface} or {@code *.AsyncIface} service interface generated by
     *                        the Apache Thrift compiler
     * @param defaultSerializationFormat the default serialization format to use when not specified by the
     *                                   client
     * @param otherAllowedSerializationFormats other serialization formats that should be supported by this
     *                                         service in addition to the default
     */
    public static THttpService ofFormats(
            Map<String, ?> implementations,
            SerializationFormat defaultSerializationFormat,
            Iterable<SerializationFormat> otherAllowedSerializationFormats) {

        requireNonNull(otherAllowedSerializationFormats, "otherAllowedSerializationFormats");

        final Set<SerializationFormat> allowedSerializationFormatsSet =
                newAllowedSerializationFormats(defaultSerializationFormat, otherAllowedSerializationFormats);

        return new THttpService(ThriftCallService.of(implementations),
                                defaultSerializationFormat, allowedSerializationFormatsSet);
    }

    /**
     * Creates a new decorator that supports all thrift protocols and defaults to
     * {@link ThriftSerializationFormats#BINARY TBinary} protocol when the client doesn't specify one.
     *
     * <p>Currently, the only way to specify a serialization format is by using the HTTP session
     * protocol and setting the Content-Type header to the appropriate {@link SerializationFormat#mediaType()}.
     */
    public static Function<Service<RpcRequest, RpcResponse>, THttpService> newDecorator() {
        return newDecorator(ThriftSerializationFormats.BINARY);
    }

    /**
     * Creates a new decorator that supports all thrift protocols and defaults to the specified
     * {@code defaultSerializationFormat} when the client doesn't specify one.
     * Currently, the only way to specify a serialization format is by using the HTTP session
     * protocol and setting the Content-Type header to the appropriate {@link SerializationFormat#mediaType()}.
     *
     * @param defaultSerializationFormat the default serialization format to use when not specified by the
     *                                   client
     */
    public static Function<Service<RpcRequest, RpcResponse>, THttpService> newDecorator(
            SerializationFormat defaultSerializationFormat) {

        return delegate -> new THttpService(delegate,
                                            defaultSerializationFormat, ThriftSerializationFormats.values());
    }

    /**
     * Creates a new decorator that supports only the formats specified and defaults to the specified
     * {@code defaultSerializationFormat} when the client doesn't specify one.
     * Currently, the only way to specify a serialization format is by using the HTTP session protocol and
     * setting the Content-Type header to the appropriate {@link SerializationFormat#mediaType()}.
     *
     * @param defaultSerializationFormat the default serialization format to use when not specified by the
     *                                   client
     * @param otherAllowedSerializationFormats other serialization formats that should be supported by this
     *                                         service in addition to the default
     */
    public static Function<Service<RpcRequest, RpcResponse>, THttpService> newDecorator(
            SerializationFormat defaultSerializationFormat,
            SerializationFormat... otherAllowedSerializationFormats) {

        requireNonNull(otherAllowedSerializationFormats, "otherAllowedSerializationFormats");
        return newDecorator(defaultSerializationFormat, Arrays.asList(otherAllowedSerializationFormats));
    }

    /**
     * Creates a new decorator that supports the protocols specified in {@code allowedSerializationFormats} and
     * defaults to the specified {@code defaultSerializationFormat} when the client doesn't specify one.
     * Currently, the only way to specify a serialization format is by using the HTTP session protocol and
     * setting the Content-Type header to the appropriate {@link SerializationFormat#mediaType()}.
     *
     * @param defaultSerializationFormat the default serialization format to use when not specified by the
     *                                   client
     * @param otherAllowedSerializationFormats other serialization formats that should be supported by this
     *                                         service in addition to the default
     */
    public static Function<Service<RpcRequest, RpcResponse>, THttpService> newDecorator(
            SerializationFormat defaultSerializationFormat,
            Iterable<SerializationFormat> otherAllowedSerializationFormats) {

        requireNonNull(otherAllowedSerializationFormats, "otherAllowedSerializationFormats");

        final Set<SerializationFormat> allowedSerializationFormatsSet =
                newAllowedSerializationFormats(defaultSerializationFormat, otherAllowedSerializationFormats);

        return delegate -> new THttpService(delegate,
                                            defaultSerializationFormat, allowedSerializationFormatsSet);
    }

    // TODO(trustin): Make this method private once we remove ThriftService.
    static ImmutableSet<SerializationFormat> newAllowedSerializationFormats(
            SerializationFormat defaultSerializationFormat,
            Iterable<SerializationFormat> otherAllowedSerializationFormats) {
        return ImmutableSet.<SerializationFormat>builder()
                .add(defaultSerializationFormat)
                .addAll(otherAllowedSerializationFormats)
                .build();
    }

    private final Service<RpcRequest, RpcResponse> delegate;
    private final SerializationFormat defaultSerializationFormat;
    private final Set<SerializationFormat> allowedSerializationFormats;
    private final ThriftCallService thriftService;

    // TODO(trustin): Make this contructor private once we remove ThriftService.
    THttpService(Service<RpcRequest, RpcResponse> delegate,
                 SerializationFormat defaultSerializationFormat,
                 Set<SerializationFormat> allowedSerializationFormats) {

        requireNonNull(delegate, "delegate");
        requireNonNull(defaultSerializationFormat, "defaultSerializationFormat");
        requireNonNull(allowedSerializationFormats, "allowedSerializationFormats");

        this.delegate = delegate;
        thriftService = findThriftService(delegate);

        this.defaultSerializationFormat = defaultSerializationFormat;
        this.allowedSerializationFormats = ImmutableSet.copyOf(allowedSerializationFormats);
    }

    private static ThriftCallService findThriftService(Service<?, ?> delegate) {
        return delegate.as(ThriftCallService.class).orElseThrow(
                    () -> new IllegalStateException("service being decorated is not a ThriftService: " +
                                                    delegate));
    }

    /**
     * Returns the information about the Thrift services being served.
     *
     * @return a {@link Map} whose key is a service name, which could be an empty string if this service
     *         is not multiplexed
     */
    public Map<String, ThriftServiceEntry> entries() {
        return thriftService.entries();
    }

    /**
     * Returns the allowed serialization formats of this service.
     */
    public Set<SerializationFormat> allowedSerializationFormats() {
        return allowedSerializationFormats;
    }

    /**
     * Returns the default serialization format of this service.
     */
    public SerializationFormat defaultSerializationFormat() {
        return defaultSerializationFormat;
    }

    @Override
    protected void doPost(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {

        final SerializationFormat serializationFormat =
                validateRequestAndDetermineSerializationFormat(req, res);

        if (serializationFormat == null) {
            return;
        }

        ctx.logBuilder().serializationFormat(serializationFormat);
        ctx.logBuilder().deferRequestContent();
        req.aggregate().handle(voidFunction((aReq, cause) -> {
            if (cause != null) {
                res.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                            MediaType.PLAIN_TEXT_UTF_8, Throwables.getStackTraceAsString(cause));
                return;
            }

            decodeAndInvoke(ctx, aReq, serializationFormat, res);
        })).exceptionally(CompletionActions::log);
    }

    private SerializationFormat validateRequestAndDetermineSerializationFormat(
            HttpRequest req, HttpResponseWriter res) {

        final HttpHeaders headers = req.headers();
        final String contentType = headers.get(HttpHeaderNames.CONTENT_TYPE);

        SerializationFormat serializationFormat;
        if (contentType != null) {
            try {
                serializationFormat = SerializationFormat.find(MediaType.parse(contentType))
                                                         .orElse(defaultSerializationFormat);
            } catch (IllegalArgumentException e) {
                logger.debug("Failed to parse the 'content-type' header: {}", contentType, e);
                serializationFormat = null;
            }

            if (serializationFormat == null ||
                !allowedSerializationFormats.contains(serializationFormat)) {
                res.respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                            MediaType.PLAIN_TEXT_UTF_8, PROTOCOL_NOT_SUPPORTED);
                return null;
            }
        } else {
            serializationFormat = defaultSerializationFormat;
        }

        final String accept = headers.get(HttpHeaderNames.ACCEPT);
        if (accept != null) {
            // If accept header is present, make sure it is sane. Currently, we do not support accept
            // headers with a different format than the content type header.
            SerializationFormat outputSerializationFormat;
            try {
                outputSerializationFormat =
                        SerializationFormat.find(MediaType.parse(accept)).orElse(serializationFormat);
            } catch (IllegalArgumentException e) {
                logger.debug("Failed to parse the 'accept' header: {}", accept, e);
                outputSerializationFormat = null;
            }
            if (outputSerializationFormat != serializationFormat) {
                res.respond(HttpStatus.NOT_ACCEPTABLE,
                            MediaType.PLAIN_TEXT_UTF_8, ACCEPT_THRIFT_PROTOCOL_MUST_MATCH_CONTENT_TYPE);
                return null;
            }
        }

        return serializationFormat;
    }

    private void decodeAndInvoke(
            ServiceRequestContext ctx, AggregatedHttpMessage req,
            SerializationFormat serializationFormat, HttpResponseWriter res) {

        final TProtocol inProto = FORMAT_TO_THREAD_LOCAL_INPUT_PROTOCOL.get(serializationFormat).get();
        inProto.reset();
        final TMemoryInputTransport inTransport = (TMemoryInputTransport) inProto.getTransport();
        final HttpData content = req.content();
        inTransport.reset(content.array(), content.offset(), content.length());

        final int seqId;
        final ThriftFunction f;
        final RpcRequest decodedReq;

        try {
            final TMessage header;
            final TBase<TBase<?, ?>, TFieldIdEnum> args;

            try {
                header = inProto.readMessageBegin();
            } catch (Exception e) {
                logger.debug("{} Failed to decode Thrift header:", ctx, e);
                res.respond(HttpStatus.BAD_REQUEST,
                            MediaType.PLAIN_TEXT_UTF_8,
                            "Failed to decode Thrift header: " + Throwables.getStackTraceAsString(e));
                return;
            }

            seqId = header.seqid;

            final byte typeValue = header.type;
            final int colonIdx = header.name.indexOf(':');
            final String serviceName;
            final String methodName;
            if (colonIdx < 0) {
                serviceName = "";
                methodName = header.name;
            } else {
                serviceName = header.name.substring(0, colonIdx);
                methodName = header.name.substring(colonIdx + 1);
            }

            // Basic sanity check. We usually should never fail here.
            if (typeValue != TMessageType.CALL && typeValue != TMessageType.ONEWAY) {
                final TApplicationException cause = new TApplicationException(
                        TApplicationException.INVALID_MESSAGE_TYPE,
                        "unexpected TMessageType: " + typeString(typeValue));

                handlePreDecodeException(ctx, res, cause, serializationFormat, seqId, methodName);
                return;
            }

            // Ensure that such a method exists.
            final ThriftServiceEntry entry = entries().get(serviceName);
            f = entry != null ? entry.metadata.function(methodName) : null;
            if (f == null) {
                final TApplicationException cause = new TApplicationException(
                        TApplicationException.UNKNOWN_METHOD, "unknown method: " + header.name);

                handlePreDecodeException(ctx, res, cause, serializationFormat, seqId, methodName);
                return;
            }

            // Decode the invocation parameters.
            try {
                if (f.isAsync()) {
                    AsyncProcessFunction<Object, TBase<TBase<?, ?>, TFieldIdEnum>, Object> asyncFunc =
                            f.asyncFunc();

                    args = asyncFunc.getEmptyArgsInstance();
                    args.read(inProto);
                    inProto.readMessageEnd();
                } else {
                    ProcessFunction<Object, TBase<TBase<?, ?>, TFieldIdEnum>> syncFunc = f.syncFunc();

                    args = syncFunc.getEmptyArgsInstance();
                    args.read(inProto);
                    inProto.readMessageEnd();
                }

                decodedReq = toRpcRequest(f.serviceType(), header.name, args);
                ctx.logBuilder().requestContent(decodedReq, new ThriftCall(header, args));
            } catch (Exception e) {
                // Failed to decode the invocation parameters.
                logger.debug("{} Failed to decode Thrift arguments:", ctx, e);

                final TApplicationException cause = new TApplicationException(
                        TApplicationException.PROTOCOL_ERROR, "failed to decode arguments: " + e);

                handlePreDecodeException(ctx, res, cause, serializationFormat, seqId, methodName);
                return;
            }
        } finally {
            inTransport.clear();
            ctx.logBuilder().requestContent(null, null);
        }

        invoke(ctx, serializationFormat, seqId, f, decodedReq, res);
    }

    private static String typeString(byte typeValue) {
        switch (typeValue) {
            case TMessageType.CALL:
                return "CALL";
            case TMessageType.REPLY:
                return "REPLY";
            case TMessageType.EXCEPTION:
                return "EXCEPTION";
            case TMessageType.ONEWAY:
                return "ONEWAY";
            default:
                return "UNKNOWN(" + (typeValue & 0xFF) + ')';
        }
    }

    private void invoke(
            ServiceRequestContext ctx, SerializationFormat serializationFormat, int seqId,
            ThriftFunction func, RpcRequest call, HttpResponseWriter res) {

        final RpcResponse reply;

        try (SafeCloseable ignored = RequestContext.push(ctx)) {
            reply = delegate.serve(ctx, call);
        } catch (Throwable cause) {
            handleException(ctx, new DefaultRpcResponse(cause), res, serializationFormat, seqId, func, cause);
            return;
        }

        reply.handle(voidFunction((result, cause) -> {
            if (cause != null) {
                handleException(ctx, reply, res, serializationFormat, seqId, func, cause);
                return;
            }

            if (func.isOneWay()) {
                handleOneWaySuccess(ctx, reply, res, serializationFormat);
                return;
            }

            try {
                handleSuccess(ctx, reply, res, serializationFormat, seqId, func, result);
            } catch (Throwable t) {
                handleException(ctx, new DefaultRpcResponse(t), res, serializationFormat, seqId, func, t);
            }
        })).exceptionally(CompletionActions::log);
    }

    private static RpcRequest toRpcRequest(Class<?> serviceType, String method, TBase<?, ?> thriftArgs) {
        requireNonNull(thriftArgs, "thriftArgs");

        @SuppressWarnings("unchecked")
        final TBase<TBase<?, ?>, TFieldIdEnum> castThriftArgs = (TBase<TBase<?, ?>, TFieldIdEnum>) thriftArgs;

        // NB: The map returned by FieldMetaData.getStructMetaDataMap() is an EnumMap,
        //     so the parameter ordering is preserved correctly during iteration.
        final Set<? extends TFieldIdEnum> fields =
                FieldMetaData.getStructMetaDataMap(castThriftArgs.getClass()).keySet();

        // Handle the case where the number of arguments is 0 or 1.
        final int numFields = fields.size();
        switch (numFields) {
            case 0:
                return RpcRequest.of(serviceType, method);
            case 1:
                return RpcRequest.of(serviceType, method,
                                     ThriftFieldAccess.get(castThriftArgs, fields.iterator().next()));
        }

        // Handle the case where the number of arguments is greater than 1.
        final List<Object> list = new ArrayList<>(numFields);
        for (TFieldIdEnum field : fields) {
            list.add(ThriftFieldAccess.get(castThriftArgs, field));
        }

        return RpcRequest.of(serviceType, method, list);
    }

    private static void handleSuccess(
            ServiceRequestContext ctx, RpcResponse rpcRes, HttpResponseWriter httpRes,
            SerializationFormat serializationFormat, int seqId, ThriftFunction func, Object returnValue) {

        TBase<TBase<?, ?>, TFieldIdEnum> wrappedResult = func.newResult();
        func.setSuccess(wrappedResult, returnValue);
        respond(serializationFormat,
                encodeSuccess(ctx, rpcRes, serializationFormat, func.name(), seqId, wrappedResult),
                httpRes);
    }

    private static void handleOneWaySuccess(
            ServiceRequestContext ctx, RpcResponse rpcRes, HttpResponseWriter httpRes,
            SerializationFormat serializationFormat) {
        ctx.logBuilder().responseContent(rpcRes, null);
        respond(serializationFormat, HttpData.EMPTY_DATA, httpRes);
    }

    private static void handleException(
            ServiceRequestContext ctx, RpcResponse rpcRes, HttpResponseWriter httpRes,
            SerializationFormat serializationFormat, int seqId, ThriftFunction func, Throwable cause) {

        final TBase<TBase<?, ?>, TFieldIdEnum> result = func.newResult();
        final HttpData content;
        if (func.setException(result, cause)) {
            content = encodeSuccess(ctx, rpcRes, serializationFormat, func.name(), seqId, result);
        } else {
            content = encodeException(ctx, rpcRes, serializationFormat, seqId, func.name(), cause);
        }


        respond(serializationFormat, content, httpRes);
    }

    private static void handlePreDecodeException(
            ServiceRequestContext ctx, HttpResponseWriter httpRes, Throwable cause,
            SerializationFormat serializationFormat, int seqId, String methodName) {

        final HttpData content = encodeException(
                ctx, new DefaultRpcResponse(cause), serializationFormat, seqId, methodName, cause);
        respond(serializationFormat, content, httpRes);
    }

    private static void respond(SerializationFormat serializationFormat,
                                HttpData content, HttpResponseWriter res) {

        res.respond(HttpStatus.OK, serializationFormat.mediaType(), content);
    }

    private static HttpData encodeSuccess(ServiceRequestContext ctx,
                                          RpcResponse reply,
                                          SerializationFormat serializationFormat,
                                          String methodName, int seqId,
                                          TBase<TBase<?, ?>, TFieldIdEnum> result) {

        final TMemoryBuffer buf = new TMemoryBuffer(128);
        final TProtocol outProto = ThriftProtocolFactories.get(serializationFormat).getProtocol(buf);

        try {
            final TMessage header = new TMessage(methodName, TMessageType.REPLY, seqId);
            outProto.writeMessageBegin(header);
            result.write(outProto);
            outProto.writeMessageEnd();

            ctx.logBuilder().responseContent(reply, new ThriftReply(header, result));
        } catch (TException e) {
            throw new Error(e); // Should never reach here.
        }

        return HttpData.of(buf.getArray(), 0, buf.length());
    }

    private static HttpData encodeException(ServiceRequestContext ctx,
                                            RpcResponse reply,
                                            SerializationFormat serializationFormat,
                                            int seqId, String methodName, Throwable cause) {

        final TApplicationException appException;
        if (cause instanceof TApplicationException) {
            appException = (TApplicationException) cause;
        } else {
            appException = new TApplicationException(
                    TApplicationException.INTERNAL_ERROR,
                    "internal server error:" + System.lineSeparator() +
                    "---- BEGIN server-side trace ----" + System.lineSeparator() +
                    Throwables.getStackTraceAsString(cause) +
                    "---- END server-side trace ----");
        }

        final TMemoryBuffer buf = new TMemoryBuffer(128);
        final TProtocol outProto = ThriftProtocolFactories.get(serializationFormat).getProtocol(buf);

        try {
            final TMessage header = new TMessage(methodName, TMessageType.EXCEPTION, seqId);
            outProto.writeMessageBegin(header);
            appException.write(outProto);
            outProto.writeMessageEnd();

            ctx.logBuilder().responseContent(reply, new ThriftReply(header, appException));
        } catch (TException e) {
            throw new Error(e); // Should never reach here.
        }

        return HttpData.of(buf.getArray(), 0, buf.length());
    }

    private static Map<SerializationFormat, ThreadLocalTProtocol> createFormatToThreadLocalTProtocolMap() {
        return ThriftSerializationFormats.values().stream().collect(
                toImmutableMap(Function.identity(),
                               f -> new ThreadLocalTProtocol(ThriftProtocolFactories.get(f))));
    }

    private static final class ThreadLocalTProtocol extends ThreadLocal<TProtocol> {

        private final TProtocolFactory protoFactory;

        private ThreadLocalTProtocol(TProtocolFactory protoFactory) {
            this.protoFactory = protoFactory;
        }

        @Override
        protected TProtocol initialValue() {
            return protoFactory.getProtocol(new TMemoryInputTransport());
        }
    }
}
