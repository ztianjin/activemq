/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.activemq.transport.ws;

import org.apache.activemq.command.BrokerInfo;
import org.apache.activemq.transport.SocketConnectorFactory;
import org.apache.activemq.transport.TransportServerSupport;
import org.apache.activemq.transport.WebTransportServerSupport;
import org.apache.activemq.util.InetAddressUtil;
import org.apache.activemq.util.IntrospectionSupport;
import org.apache.activemq.util.ServiceStopper;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;

/**
 * Creates a web server and registers web socket server
 *
 */
public class WSTransportServer extends WebTransportServerSupport {

    public WSTransportServer(URI location) {
        super(location);
        this.bindAddress = location;
        socketConnectorFactory = new SocketConnectorFactory();
    }

    protected void doStart() throws Exception {
        server = new Server();

        if (connector == null) {
            connector = socketConnectorFactory.createConnector();
        }

        URI bind = getBindLocation();

        bind();

        ServletContextHandler contextHandler =
                new ServletContextHandler(server, "/", ServletContextHandler.NO_SECURITY);

        ServletHolder holder = new ServletHolder();
        Map<String, Object> webSocketOptions = IntrospectionSupport.extractProperties(transportOptions, "websocket.");
        for(Map.Entry<String,Object> webSocketEntry : webSocketOptions.entrySet()) {
            Object value = webSocketEntry.getValue();
            if (value != null) {
                holder.setInitParameter(webSocketEntry.getKey(), value.toString());
            }
        }

        holder.setServlet(new StompServlet());
        contextHandler.addServlet(holder, "/");

        contextHandler.setAttribute("acceptListener", getAcceptListener());

        server.start();
        setConnectURI(new URI(bind.getScheme(), bind.getUserInfo(), host, connector.getLocalPort(), bind.getPath(), bind.getQuery(), bind.getFragment()));
    }

    protected void doStop(ServiceStopper stopper) throws Exception {
        Server temp = server;
        server = null;
        if (temp != null) {
            temp.stop();
        }
    }

    public InetSocketAddress getSocketAddress() {
        return null;
    }

    public void setBrokerInfo(BrokerInfo brokerInfo) {
    }

    protected void setConnector(Connector connector) {
        this.connector = connector;
    }

    @Override
    public void setTransportOption(Map<String, Object> transportOptions) {
        Map<String, Object> socketOptions = IntrospectionSupport.extractProperties(transportOptions, "transport.");
        socketConnectorFactory.setTransportOptions(socketOptions);
        super.setTransportOption(transportOptions);
    }

}
