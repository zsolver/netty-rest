package org.rakam.server.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import io.airlift.log.Logger;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.internal.ConcurrentSet;
import io.swagger.models.Swagger;
import io.swagger.util.Json;
import io.swagger.util.PrimitiveType;
import org.rakam.server.http.HttpServerHandler.DebugHttpServerHandler;
import org.rakam.server.http.IRequestParameter.BodyParameter;
import org.rakam.server.http.IRequestParameter.HeaderParameter;
import org.rakam.server.http.annotations.ApiParam;
import org.rakam.server.http.annotations.CookieParam;
import org.rakam.server.http.annotations.HeaderParam;
import org.rakam.server.http.annotations.JsonRequest;
import org.rakam.server.http.annotations.ParamBody;
import org.rakam.server.http.util.Lambda;
import org.rakam.server.http.util.Os;
import sun.reflect.generics.reflectiveObjects.ParameterizedTypeImpl;
import sun.reflect.generics.reflectiveObjects.TypeVariableImpl;

import javax.ws.rs.Path;
import java.io.UnsupportedEncodingException;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpMethod.POST;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static java.lang.String.format;
import static java.lang.invoke.MethodHandles.lookup;
import static java.util.Objects.requireNonNull;
import static org.rakam.server.http.HttpServerHandler.DebugHttpServerHandler.START_TIME;
import static org.rakam.server.http.util.Lambda.produceLambdaForFunction;

public class HttpServer {
    private final static Logger LOGGER = Logger.get(HttpServer.class);
    static final ObjectMapper DEFAULT_MAPPER;

    public final RouteMatcher routeMatcher;
    private final Swagger swagger;

    private final ObjectMapper mapper;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final PreProcessors preProcessors;
    private final boolean debugMode;
    private final boolean proxyProtocol;
    private Channel channel;

    private final ImmutableMap<Class, PrimitiveType> swaggerBeanMappings = ImmutableMap.<Class, PrimitiveType>builder()
            .put(LocalDate.class, PrimitiveType.DATE)
            .put(Duration.class, PrimitiveType.STRING)
            .put(Instant.class, PrimitiveType.DATE_TIME)
            .build();

    static {
        DEFAULT_MAPPER = new ObjectMapper();
    }


    HttpServer(Set<HttpService> httpServicePlugins, Set<WebSocketService> websocketServices, Swagger swagger, EventLoopGroup eventLoopGroup, PreProcessors preProcessors, ObjectMapper mapper, Map<Class, PrimitiveType> overridenMappings, boolean debugMode, boolean proxyProtocol) {
        this.routeMatcher = new RouteMatcher(debugMode);
        this.preProcessors = preProcessors;
        this.workerGroup = requireNonNull(eventLoopGroup, "eventLoopGroup is null");
        this.swagger = requireNonNull(swagger, "swagger is null");
        this.mapper = mapper;
        this.debugMode = debugMode;
        this.proxyProtocol = proxyProtocol;

        this.bossGroup = Os.supportsEpoll() ? new EpollEventLoopGroup(1) : new NioEventLoopGroup(1);
        registerEndPoints(requireNonNull(httpServicePlugins, "httpServices is null"), overridenMappings);
        registerWebSocketPaths(requireNonNull(websocketServices, "webSocketServices is null"));
        routeMatcher.add(HttpMethod.GET, "/api/swagger.json", this::swaggerApiHandle);
    }

    public void setNotFoundHandler(HttpRequestHandler handler) {
        routeMatcher.noMatch(handler);
    }

    private void swaggerApiHandle(RakamHttpRequest request) {
        String content;

        try {
            content = Json.mapper().writeValueAsString(swagger);
        } catch (JsonProcessingException e) {
            request.response("Error").end();
            return;
        }

        request.response(content).end();
    }

    private void registerWebSocketPaths(Set<WebSocketService> webSocketServices) {
        webSocketServices.forEach(service -> {
            String path = service.getClass().getAnnotation(Path.class).value();
            if (path == null) {
                throw new IllegalStateException(format("Classes that implement WebSocketService must have %s annotation.",
                        Path.class.getCanonicalName()));
            }
            routeMatcher.add(path, service);
        });
    }

    private void registerEndPoints(Set<HttpService> httpServicePlugins, Map<Class, PrimitiveType> overriddenMappings) {
        Map<Class, PrimitiveType> swaggerBeanMappings;
        if (overriddenMappings != null) {
            swaggerBeanMappings = ImmutableMap.<Class, PrimitiveType>builder().putAll(this.swaggerBeanMappings).putAll(overriddenMappings).build();
        } else {
            swaggerBeanMappings = this.swaggerBeanMappings;
        }
        SwaggerReader reader = new SwaggerReader(swagger, mapper, swaggerBeanMappings);

        httpServicePlugins.forEach(service -> {

            reader.read(service.getClass());
            if (!service.getClass().isAnnotationPresent(Path.class)) {
                throw new IllegalStateException(format("HttpService class %s must have javax.ws.rs.Path annotation", service.getClass()));
            }
            String mainPath = service.getClass().getAnnotation(Path.class).value();
            if (mainPath == null) {
                throw new IllegalStateException(format("Classes that implement HttpService must have %s annotation.", Path.class.getCanonicalName()));
            }
            RouteMatcher.MicroRouteMatcher microRouteMatcher = new RouteMatcher.MicroRouteMatcher(routeMatcher, mainPath);

            for (Method method : service.getClass().getMethods()) {
                Path annotation = method.getAnnotation(Path.class);

                if (annotation == null) {
                    continue;
                }

                method.setAccessible(true);
                String lastPath = annotation.value();
                Iterator<HttpMethod> methodExists = Arrays.stream(method.getAnnotations())
                        .filter(ann -> ann.annotationType().isAnnotationPresent(javax.ws.rs.HttpMethod.class))
                        .map(ann -> HttpMethod.valueOf(ann.annotationType().getAnnotation(javax.ws.rs.HttpMethod.class).value()))
                        .iterator();

                final JsonRequest jsonRequest = method.getAnnotation(JsonRequest.class);

                // if no @Path annotation exists and @JsonRequest annotation is present, bind POST httpMethod by default.
                if (!methodExists.hasNext() && jsonRequest != null) {
                    microRouteMatcher.add(lastPath, POST, getJsonRequestHandler(method, service));
                } else {
                    while (methodExists.hasNext()) {
                        HttpMethod httpMethod = methodExists.next();

                        if (jsonRequest != null && httpMethod != POST) {
                            if (Arrays.stream(method.getParameters()).anyMatch(p -> p.isAnnotationPresent(ApiParam.class))) {
                                throw new IllegalStateException("@ApiParam annotation can only be used within POST requests");
                            }
                            if (method.getParameterCount() == 1 && method.getParameters()[0].isAnnotationPresent(ParamBody.class)) {
                                throw new IllegalStateException("@ParamBody annotation can only be used within POST requests");
                            }
                        }

                        HttpRequestHandler handler;
                        if (jsonRequest != null) {
                            handler = getJsonRequestHandler(method, service);
                        } else if (httpMethod == HttpMethod.GET && !method.getReturnType().equals(void.class)) {
                            handler = createGetRequestHandler(service, method);
                        } else {
                            handler = generateRawRequestHandler(service, method);
                        }

                        microRouteMatcher.add(lastPath.equals("/") ? "" : lastPath, httpMethod, handler);
                    }
                }
            }
        });
    }

    private HttpRequestHandler getJsonRequestHandler(Method method, HttpService service) {
        final List<RequestPreprocessor<RakamHttpRequest>> preprocessorRequest = getPreprocessorRequest(method);

        if (method.getParameterCount() == 1 && method.getParameters()[0].getAnnotation(ParamBody.class) != null) {
            return new JsonBeanRequestHandler(mapper, method,
                    getPreprocessorForJsonBeanRequest(method),
                    preprocessorRequest,
                    service);
        }

        ArrayList<IRequestParameter> bodyParams = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            if (parameter.isAnnotationPresent(ApiParam.class)) {
                ApiParam apiParam = parameter.getAnnotation(ApiParam.class);
                bodyParams.add(new BodyParameter(mapper, apiParam.name(), getActualType(service.getClass(), parameter.getParameterizedType()),
                        apiParam == null ? false : apiParam.required()));
            } else if (parameter.isAnnotationPresent(HeaderParam.class)) {
                HeaderParam param = parameter.getAnnotation(HeaderParam.class);
                bodyParams.add(new HeaderParameter(param.value(), param.required()));
            } else if (parameter.isAnnotationPresent(CookieParam.class)) {
                CookieParam param = parameter.getAnnotation(CookieParam.class);
                bodyParams.add(new IRequestParameter.CookieParameter(param.name(), param.required()));
            } else {
                bodyParams.add(new BodyParameter(mapper, parameter.getName(), parameter.getParameterizedType(), false));
            }
        }

        boolean isAsync = CompletionStage.class.isAssignableFrom(method.getReturnType());

        final List<RequestPreprocessor<ObjectNode>> preprocessorForJsonRequest = getPreprocessorForJsonRequest(method);

        if (bodyParams.size() == 0) {
            final ObjectNode emptyNode = mapper.createObjectNode();
            final Function<HttpService, Object> lambda = Lambda.produceLambdaForFunction(method);
            return request -> {
                Object invoke;
                try {
                    if (!preprocessorForJsonRequest.isEmpty()) {
                        for (RequestPreprocessor<ObjectNode> preprocessor : preprocessorForJsonRequest) {
                            preprocessor.handle(request.headers(), emptyNode);
                        }
                    }

                    if (!preprocessorRequest.isEmpty()) {
                        HttpServer.applyPreprocessors(request, preprocessorRequest);
                    }

                    invoke = lambda.apply(service);
                } catch (Throwable e) {
                    requestError(e.getCause(), request);
                    return;
                }

                handleRequest(mapper, isAsync, invoke, request);
            };
        } else {
            // TODO: we may specialize for a number of parameters to avoid generic MethodHandle invoker and use lambda generation instead.
            MethodHandle methodHandle;
            try {
                methodHandle = lookup().unreflect(method);
            } catch (IllegalAccessException e) {
                throw Throwables.propagate(e);
            }

            return new JsonParametrizedRequestHandler(mapper, bodyParams,
                    methodHandle, service,
                    preprocessorForJsonRequest,
                    preprocessorRequest, isAsync);
        }
    }

    private Type getActualType(Class readClass, Type parameterizedType) {
        // if the parameter has a generic type, it will be read as Object
        // so we need to find the actual implementation and return that type.
        if (parameterizedType instanceof TypeVariableImpl) {
            TypeVariable[] genericParameters = readClass.getSuperclass().getTypeParameters();
            Type[] implementations = ((ParameterizedTypeImpl) readClass.getGenericSuperclass()).getActualTypeArguments();
            for (int i = 0; i < genericParameters.length; i++) {
                if (genericParameters[i].getName().equals(((TypeVariableImpl) parameterizedType).getName())) {
                    return implementations[i];
                }
            }
        }
        return parameterizedType;
    }

    private List<RequestPreprocessor<ObjectNode>> getPreprocessorForJsonRequest(Method method) {
        return preProcessors.jsonRequestPreprocessors.stream()
                .filter(p -> p.test(method)).map(p -> p.getPreprocessor()).collect(Collectors.toList());
    }

    private List<RequestPreprocessor<Object>> getPreprocessorForJsonBeanRequest(Method method) {
        return preProcessors.jsonBeanRequestPreprocessors.stream()
                .filter(p -> p.test(method)).map(p -> p.getPreprocessor()).collect(Collectors.toList());
    }

    private List<RequestPreprocessor<RakamHttpRequest>> getPreprocessorRequest(Method method) {
        return preProcessors.requestPreprocessors.stream()
                .filter(p -> p.test(method)).map(p -> p.getPreprocessor()).collect(Collectors.toList());
    }

    static void handleRequest(ObjectMapper mapper, boolean isAsync, Object invoke, RakamHttpRequest request) {
        if (isAsync) {
            handleAsyncJsonRequest(mapper, request, (CompletionStage) invoke);
        } else {
            returnResponse(mapper, request, OK, invoke);
        }
    }

    private HttpRequestHandler generateRawRequestHandler(HttpService service, Method method) {
        if (!method.getReturnType().equals(void.class) ||
                method.getParameterCount() != 1 ||
                !method.getParameterTypes()[0].equals(RakamHttpRequest.class)) {
            throw new IllegalStateException(format("The signature of HTTP request methods must be [void ()]", RakamHttpRequest.class.getCanonicalName()));
        }

        List<RequestPreprocessor<RakamHttpRequest>> requestPreprocessors = getPreprocessorRequest(method);

        // we don't need to pass service object is the method is static.
        // it's also better for performance since there will be only one object to send the stack.
        if (Modifier.isStatic(method.getModifiers())) {
            Consumer<RakamHttpRequest> lambda;
            lambda = Lambda.produceLambdaForConsumer(method);
            return request -> {
                try {
                    if (!requestPreprocessors.isEmpty()) {
                        HttpServer.applyPreprocessors(request, requestPreprocessors);
                    }

                    lambda.accept(request);
                } catch (Exception e) {
                    requestError(e, request);
                }
            };
        } else {
            BiConsumer<HttpService, RakamHttpRequest> lambda;
            lambda = Lambda.produceLambdaForBiConsumer(method);
            return request -> {
                try {
                    lambda.accept(service, request);
                } catch (Exception e) {
                    requestError(e, request);
                }
            };
        }
    }

    private HttpRequestHandler createGetRequestHandler(HttpService service, Method method) {
        boolean isAsync = CompletionStage.class.isAssignableFrom(method.getReturnType());

        final List<RequestPreprocessor<RakamHttpRequest>> preprocessors = getPreprocessorRequest(method);

        if (method.getParameterCount() == 0) {
            Function<HttpService, Object> function = produceLambdaForFunction(method);
            return (request) -> {
                try {
                    if (!preprocessors.isEmpty()) {
                        applyPreprocessors(request, preprocessors);
                    }
                } catch (Throwable e) {
                    requestError(e, request);
                    return;
                }

                if (isAsync) {
                    CompletionStage apply;
                    try {
                        apply = (CompletionStage) function.apply(service);
                    } catch (Exception e) {
                        requestError(e.getCause(), request);
                        return;
                    }
                    handleAsyncJsonRequest(mapper, request, apply);
                } else {
                    handleJsonRequest(mapper, service, request, function);
                }
            };
        } else {
            BiFunction<HttpService, Object, Object> function = Lambda.produceLambdaForBiFunction(method);

            if (method.getParameterTypes()[0].equals(ObjectNode.class)) {
                return request -> {
                    try {
                        if (!preprocessors.isEmpty()) {
                            HttpServer.applyPreprocessors(request, preprocessors);
                        }
                    } catch (Throwable e) {
                        requestError(e, request);
                        return;
                    }

                    ObjectNode json = generate(request.params());

                    if (isAsync) {
                        CompletionStage apply;
                        try {
                            apply = (CompletionStage) function.apply(service, json);
                        } catch (Exception e) {
                            requestError(e.getCause(), request);
                            return;
                        }
                        handleAsyncJsonRequest(mapper, request, apply);
                    } else {
                        handleJsonRequest(mapper, service, request, function, json);
                    }
                };
            } else {
                return request -> {
                    try {
                        if (!preprocessors.isEmpty()) {
                            HttpServer.applyPreprocessors(request, preprocessors);
                        }
                    } catch (Throwable e) {
                        requestError(e, request);
                        return;
                    }

                    ObjectNode json = generate(request.params());
                    if (isAsync) {
                        CompletionStage apply;
                        try {
                            apply = (CompletionStage) function.apply(service, json);
                        } catch (Exception e) {
                            requestError(e.getCause(), request);
                            return;
                        }
                        handleAsyncJsonRequest(mapper, request, apply);
                    } else {
                        handleJsonRequest(mapper, service, request, function, json);
                    }
                };
            }
        }
    }


    static void handleJsonRequest(ObjectMapper mapper, HttpService serviceInstance, RakamHttpRequest request, BiFunction<HttpService, Object, Object> function, Object json) {
        try {
            Object apply = function.apply(serviceInstance, json);
            returnResponse(mapper, request, OK, apply);
        } catch (HttpRequestException e) {
            HttpResponseStatus statusCode = e.getStatusCode();
            returnResponse(mapper, request, statusCode, errorMessage(e.getMessage(), statusCode));
        } catch (Exception e) {
            LOGGER.error(e, "An uncaught exception raised while processing request.");
            ObjectNode errorMessage = errorMessage("error processing request.", INTERNAL_SERVER_ERROR);
            returnResponse(mapper, request, BAD_REQUEST, errorMessage);
        }
    }

    static void handleJsonRequest(ObjectMapper mapper, HttpService serviceInstance, RakamHttpRequest request, Function<HttpService, Object> function) {
        try {
            Object apply = function.apply(serviceInstance);
            returnResponse(mapper, request, OK, apply);
        } catch (HttpRequestException e) {
            HttpResponseStatus statusCode = e.getStatusCode();
            returnResponse(mapper, request, statusCode, errorMessage(e.getMessage(), statusCode));
        } catch (Exception e) {
            LOGGER.error(e, "An uncaught exception raised while processing request.");
            ObjectNode errorMessage = errorMessage("error processing request.", INTERNAL_SERVER_ERROR);
            returnResponse(mapper, request, BAD_REQUEST, errorMessage);
        }
    }

    private static void returnResponse(ObjectMapper mapper, RakamHttpRequest request, HttpResponseStatus status, Object apply) {
        try {
            if (apply instanceof Response) {
                final Response apply1 = (Response) apply;
                final byte[] bytes = mapper.writeValueAsBytes(apply1.getData());
                final ByteBuf byteBuf = Unpooled.wrappedBuffer(bytes);
                final DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, byteBuf);
                if (apply1.getCookies() != null) {
                    response.headers().add(HttpHeaders.Names.SET_COOKIE,
                            ServerCookieEncoder.STRICT.encode(apply1.getCookies()));
                }
                request.response(response).end();
            } else {
                request.response(mapper.writeValueAsString(apply), status).end();
            }
        } catch (JsonProcessingException e) {
            LOGGER.error(e, "Couldn't serialize returned object");
            throw new RuntimeException("couldn't serialize object", e);
        }
    }

    static void handleAsyncJsonRequest(ObjectMapper mapper, RakamHttpRequest request, CompletionStage apply) {
        apply.whenComplete((BiConsumer<Object, Throwable>) (result, ex) -> {
            if (ex != null) {
                while (ex instanceof CompletionException) {
                    ex = ex.getCause();
                }
                if (ex instanceof HttpRequestException) {
                    HttpResponseStatus statusCode = ((HttpRequestException) ex).getStatusCode();
                    returnResponse(mapper, request, statusCode, errorMessage(ex.getMessage(), statusCode));
                } else {
                    returnResponse(mapper, request, INTERNAL_SERVER_ERROR, errorMessage(INTERNAL_SERVER_ERROR.reasonPhrase(), INTERNAL_SERVER_ERROR));
                    LOGGER.error(ex, "Error while processing request");
                }
            } else {
                if (result instanceof String) {
                    request.response((String) result).end();
                } else {
                    try {
                        String encode = mapper.writeValueAsString(result);
                        request.response(encode).end();
                    } catch (JsonProcessingException e) {
                        request.response(String.format("Couldn't serialize class %s : %s",
                                result.getClass().getCanonicalName(), e.getMessage())).end();
                    }
                }
            }
        });
    }

    public void bind(String host, int port)
            throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap();
        b.option(ChannelOption.SO_BACKLOG, 1024);

        ConcurrentSet<ChannelHandlerContext> activeChannels = new ConcurrentSet();

        if (debugMode) {
            routeMatcher.add(HttpMethod.GET, "/active-client/count",
                    request -> request.response(Integer.toString(activeChannels.size())).end());

            routeMatcher.add(HttpMethod.GET, "/active-client/list", request -> {
                int now = (int) (System.currentTimeMillis() / 1000);

                String collect = activeChannels.stream().map(c -> {
                    String s = c.channel().remoteAddress().toString();
                    Integer integer = c.attr(START_TIME).get();
                    if (integer != null) {
                        s += " " + (now - integer) + "s ";
                    } else {
                        s += " ? ";
                    }
                    s += c.attr(RouteMatcher.PATH).get();
                    if (request.context().channel().equals(c)) {
                        s += " *";
                    }
                    return s;
                }).collect(Collectors.joining("\n"));

                try {
                    DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                            HttpResponseStatus.OK, Unpooled.wrappedBuffer(collect.getBytes("UTF-8")));
                    response.headers().set(CONTENT_TYPE, "text/plain");
                    request.response(response).end();
                } catch (UnsupportedEncodingException e) {
                    throw Throwables.propagate(e);
                }
            });
        }

        b.group(bossGroup, workerGroup)
                .channel(Os.supportsEpoll() ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch)
                            throws Exception {
                        ChannelPipeline p = ch.pipeline();
                        HttpServerHandler handler;
                        if (proxyProtocol) {
                            p.addLast(new HAProxyMessageDecoder());
                            handler = new HaProxyBackendServerHandler(routeMatcher);
                        } else {
                            handler = new HttpServerHandler(routeMatcher);
                        }

                        p.addLast("httpCodec", new HttpServerCodec());
                        if (debugMode) {
                            p.addLast("serverHandler", new DebugHttpServerHandler(activeChannels, handler));
                        } else {
                            p.addLast("serverHandler", handler);
                        }

                    }
                });

        channel = b.bind(host, port).sync().channel();

    }

    public void stop() {
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    static void applyPreprocessors(RakamHttpRequest request, List<RequestPreprocessor<RakamHttpRequest>> preprocessors) {
        for (RequestPreprocessor<RakamHttpRequest> preprocessor : preprocessors) {
            preprocessor.handle(request.headers(), request);
        }
    }

    public static void returnError(RakamHttpRequest request, String message, HttpResponseStatus statusCode) {
        ObjectNode obj = DEFAULT_MAPPER.createObjectNode()
                .put("error", message)
                .put("error_code", statusCode.code());

        String bytes;
        try {
            bytes = DEFAULT_MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException();
        }
        request.response(bytes, statusCode)
                .headers().set("Content-Type", "application/json; charset=utf-8");
        request.end();
    }

    public static ObjectNode errorMessage(String message, HttpResponseStatus statusCode) {
        return DEFAULT_MAPPER.createObjectNode()
                .put("error", message)
                .put("error_code", statusCode.code());
    }

    static void requestError(Throwable ex, RakamHttpRequest request) {
        if (ex instanceof HttpRequestException) {
            HttpResponseStatus statusCode = ((HttpRequestException) ex).getStatusCode();
            returnError(request, ex.getMessage(), statusCode);
        } else {
            LOGGER.error(ex, "An uncaught exception raised while processing request.");
            returnError(request, "error processing request: " + ex.getMessage(), INTERNAL_SERVER_ERROR);
        }
    }

    public ObjectNode generate(Map<String, List<String>> map) {
        ObjectNode obj = mapper.createObjectNode();
        for (Map.Entry<String, List<String>> item : map.entrySet()) {
            String key = item.getKey();
            obj.put(key, item.getValue().get(0));
        }
        return obj;
    }
}