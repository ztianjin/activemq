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
package org.apache.activemq.broker.jmx;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.jms.BytesMessage;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.Topic;
import javax.management.MBeanServer;
import javax.management.MBeanServerInvocationHandler;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import junit.textui.TestRunner;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.ActiveMQSession;
import org.apache.activemq.BlobMessage;
import org.apache.activemq.EmbeddedBrokerTestSupport;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.region.BaseDestination;
import org.apache.activemq.broker.region.policy.PolicyEntry;
import org.apache.activemq.broker.region.policy.PolicyMap;
import org.apache.activemq.broker.region.policy.SharedDeadLetterStrategy;
import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.ActiveMQQueue;
import org.apache.activemq.command.ActiveMQTempQueue;
import org.apache.activemq.util.JMXSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A test case of the various MBeans in ActiveMQ. If you want to look at the
 * various MBeans after the test has been run then run this test case as a
 * command line application.
 */
public class MBeanTest extends EmbeddedBrokerTestSupport {
    private static final Logger LOG = LoggerFactory.getLogger(MBeanTest.class);

    private static boolean waitForKeyPress;

    protected MBeanServer mbeanServer;
    protected String domain = "org.apache.activemq";
    protected String clientID = "foo";

    protected Connection connection;
    protected boolean transacted;
    protected int authMode = Session.AUTO_ACKNOWLEDGE;
    protected static final int MESSAGE_COUNT = 2*BaseDestination.MAX_PAGE_SIZE;

    /**
     * When you run this test case from the command line it will pause before
     * terminating so that you can look at the MBeans state for debugging
     * purposes.
     */
    public static void main(String[] args) {
        waitForKeyPress = true;
        TestRunner.run(MBeanTest.class);
    }

    public void testConnectors() throws Exception{
        ObjectName brokerName = assertRegisteredObjectName(domain + ":Type=Broker,BrokerName=localhost");
        BrokerViewMBean broker = (BrokerViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, brokerName, BrokerViewMBean.class, true);

        assertEquals("openwire URL port doesn't equal bind Address",
                     new URI(broker.getTransportConnectorByType("tcp")).getPort(),
                     new URI(this.broker.getTransportConnectors().get(0).getPublishableConnectString()).getPort());
    }

    public void testMBeans() throws Exception {
        connection = connectionFactory.createConnection();
        useConnection(connection);

        // test all the various MBeans now we have a producer, consumer and
        // messages on a queue
        assertSendViaMBean();
        assertQueueBrowseWorks();
        assertCreateAndDestroyDurableSubscriptions();
        assertConsumerCounts();
        assertProducerCounts();
    }

    public void testMoveMessages() throws Exception {
        connection = connectionFactory.createConnection();
        useConnection(connection);

        ObjectName queueViewMBeanName = assertRegisteredObjectName(domain + ":Type=Queue,Destination=" + getDestinationString() + ",BrokerName=localhost");

        QueueViewMBean queue = (QueueViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, queueViewMBeanName, QueueViewMBean.class, true);

        CompositeData[] compdatalist = queue.browse();
        int initialQueueSize = compdatalist.length;
        if (initialQueueSize == 0) {
            fail("There is no message in the queue:");
        }
        else {
            echo("Current queue size: " + initialQueueSize);
        }
        int messageCount = initialQueueSize;
        String[] messageIDs = new String[messageCount];
        for (int i = 0; i < messageCount; i++) {
            CompositeData cdata = compdatalist[i];
            String messageID = (String) cdata.get("JMSMessageID");
            assertNotNull("Should have a message ID for message " + i, messageID);
            messageIDs[i] = messageID;
        }

        assertTrue("dest has some memory usage", queue.getMemoryPercentUsage() > 0);

        echo("About to move " + messageCount + " messages");

        String newDestination = getSecondDestinationString();
        for (String messageID : messageIDs) {
            echo("Moving message: " + messageID);
            queue.moveMessageTo(messageID, newDestination);
        }

        echo("Now browsing the queue");
        compdatalist = queue.browse();
        int actualCount = compdatalist.length;
        echo("Current queue size: " + actualCount);
        assertEquals("Should now have empty queue but was", initialQueueSize - messageCount, actualCount);

        echo("Now browsing the second queue");

        queueViewMBeanName = assertRegisteredObjectName(domain + ":Type=Queue,Destination=" + newDestination + ",BrokerName=localhost");
        QueueViewMBean queueNew = (QueueViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, queueViewMBeanName, QueueViewMBean.class, true);

        long newQueuesize = queueNew.getQueueSize();
        echo("Second queue size: " + newQueuesize);
        assertEquals("Unexpected number of messages ",messageCount, newQueuesize);

        // check memory usage migration
        assertTrue("new dest has some memory usage", queueNew.getMemoryPercentUsage() > 0);
        assertEquals("old dest has no memory usage", 0, queue.getMemoryPercentUsage());
        assertTrue("use cache", queueNew.isUseCache());
        assertTrue("cache enabled", queueNew.isCacheEnabled());
    }

    public void testRemoveMessages() throws Exception {
        ObjectName brokerName = assertRegisteredObjectName(domain + ":Type=Broker,BrokerName=localhost");
        BrokerViewMBean broker = (BrokerViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, brokerName, BrokerViewMBean.class, true);
        broker.addQueue(getDestinationString());

        ObjectName queueViewMBeanName = assertRegisteredObjectName(domain + ":Type=Queue,Destination=" + getDestinationString() + ",BrokerName=localhost");

        QueueViewMBean queue = (QueueViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, queueViewMBeanName, QueueViewMBean.class, true);
        String msg1 = queue.sendTextMessage("message 1");
        String msg2 = queue.sendTextMessage("message 2");

        assertTrue(queue.removeMessage(msg2));

        connection = connectionFactory.createConnection();
        connection.start();
        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        ActiveMQDestination dest = createDestination();

        MessageConsumer consumer = session.createConsumer(dest);
        Message message = consumer.receive(1000);
        assertNotNull(message);
        assertEquals(msg1, message.getJMSMessageID());

        String msg3 = queue.sendTextMessage("message 3");
        message = consumer.receive(1000);
        assertNotNull(message);
        assertEquals(msg3, message.getJMSMessageID());

        message = consumer.receive(1000);
        assertNull(message);

    }

    public void testRetryMessages() throws Exception {
        // lets speed up redelivery
        ActiveMQConnectionFactory factory = (ActiveMQConnectionFactory) connectionFactory;
        factory.getRedeliveryPolicy().setCollisionAvoidancePercent((short) 0);
        factory.getRedeliveryPolicy().setMaximumRedeliveries(1);
        factory.getRedeliveryPolicy().setInitialRedeliveryDelay(0);
        factory.getRedeliveryPolicy().setUseCollisionAvoidance(false);
        factory.getRedeliveryPolicy().setUseExponentialBackOff(false);
        factory.getRedeliveryPolicy().setBackOffMultiplier((short) 0);

        connection = connectionFactory.createConnection();
        useConnection(connection);

        ObjectName queueViewMBeanName = assertRegisteredObjectName(domain + ":Type=Queue,Destination=" + getDestinationString() + ",BrokerName=localhost");
        QueueViewMBean queue = (QueueViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, queueViewMBeanName, QueueViewMBean.class, true);

        long initialQueueSize = queue.getQueueSize();
        echo("current queue size: " + initialQueueSize);
        assertTrue("dest has some memory usage", queue.getMemoryPercentUsage() > 0);

        // lets create a duff consumer which keeps rolling back...
        Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
        MessageConsumer consumer = session.createConsumer(new ActiveMQQueue(getDestinationString()));
        Message message = consumer.receive(5000);
        while (message != null) {
            echo("Message: " + message.getJMSMessageID() + " redelivered " + message.getJMSRedelivered() + " counter " + message.getObjectProperty("JMSXDeliveryCount"));
            session.rollback();
            message = consumer.receive(2000);
        }
        consumer.close();
        session.close();

        // now lets get the dead letter queue
        Thread.sleep(1000);

        ObjectName dlqQueueViewMBeanName = assertRegisteredObjectName(domain + ":Type=Queue,Destination=" + SharedDeadLetterStrategy.DEFAULT_DEAD_LETTER_QUEUE_NAME + ",BrokerName=localhost");
        QueueViewMBean dlq = (QueueViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, dlqQueueViewMBeanName, QueueViewMBean.class, true);

        long initialDlqSize = dlq.getQueueSize();
        CompositeData[] compdatalist = dlq.browse();
        int dlqQueueSize = compdatalist.length;
        if (dlqQueueSize == 0) {
            fail("There are no messages in the queue:");
        }
        else {
            echo("Current DLQ queue size: " + dlqQueueSize);
        }
        int messageCount = dlqQueueSize;
        String[] messageIDs = new String[messageCount];
        for (int i = 0; i < messageCount; i++) {
            CompositeData cdata = compdatalist[i];
            String messageID = (String) cdata.get("JMSMessageID");
            assertNotNull("Should have a message ID for message " + i, messageID);
            messageIDs[i] = messageID;
        }

        int dlqMemUsage = dlq.getMemoryPercentUsage();
        assertTrue("dlq has some memory usage", dlqMemUsage > 0);
        assertEquals("dest has no memory usage", 0, queue.getMemoryPercentUsage());

        echo("About to retry " + messageCount + " messages");

        for (String messageID : messageIDs) {
            echo("Retrying message: " + messageID);
            dlq.retryMessage(messageID);
        }

        long queueSize = queue.getQueueSize();
        compdatalist = queue.browse();
        int actualCount = compdatalist.length;
        echo("Orginal queue size is now " + queueSize);
        echo("Original browse queue size: " + actualCount);

        long dlqSize = dlq.getQueueSize();
        echo("DLQ size: " + dlqSize);

        assertEquals("DLQ size", initialDlqSize - messageCount, dlqSize);
        assertEquals("queue size", initialQueueSize, queueSize);
        assertEquals("browse queue size", initialQueueSize, actualCount);

        assertEquals("dest has some memory usage", dlqMemUsage, queue.getMemoryPercentUsage());
    }

    public void testMoveMessagesBySelector() throws Exception {
        connection = connectionFactory.createConnection();
        useConnection(connection);

        ObjectName queueViewMBeanName = assertRegisteredObjectName(domain + ":Type=Queue,Destination=" + getDestinationString() + ",BrokerName=localhost");

        QueueViewMBean queue = (QueueViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, queueViewMBeanName, QueueViewMBean.class, true);

        String newDestination = getSecondDestinationString();
        queue.moveMatchingMessagesTo("counter > 2", newDestination);

        queueViewMBeanName = assertRegisteredObjectName(domain + ":Type=Queue,Destination=" + newDestination + ",BrokerName=localhost");

        queue = (QueueViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, queueViewMBeanName, QueueViewMBean.class, true);
        int movedSize = MESSAGE_COUNT-3;
        assertEquals("Unexpected number of messages ",movedSize,queue.getQueueSize());

        // now lets remove them by selector
        queue.removeMatchingMessages("counter > 2");

        assertEquals("Should have no more messages in the queue: " + queueViewMBeanName, 0, queue.getQueueSize());
        assertEquals("dest has no memory usage", 0, queue.getMemoryPercentUsage());
    }

    public void testCopyMessagesBySelector() throws Exception {
        connection = connectionFactory.createConnection();
        useConnection(connection);

        ObjectName queueViewMBeanName = assertRegisteredObjectName(domain + ":Type=Queue,Destination=" + getDestinationString() + ",BrokerName=localhost");

        QueueViewMBean queue = (QueueViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, queueViewMBeanName, QueueViewMBean.class, true);

        String newDestination = getSecondDestinationString();
        long queueSize = queue.getQueueSize();
        assertTrue(queueSize > 0);
        queue.copyMatchingMessagesTo("counter > 2", newDestination);

        queueViewMBeanName = assertRegisteredObjectName(domain + ":Type=Queue,Destination=" + newDestination + ",BrokerName=localhost");

        queue = (QueueViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, queueViewMBeanName, QueueViewMBean.class, true);

        LOG.info("Queue: " + queueViewMBeanName + " now has: " + queue.getQueueSize() + " message(s)");
        assertEquals("Expected messages in a queue: " + queueViewMBeanName, MESSAGE_COUNT-3, queue.getQueueSize());
        // now lets remove them by selector
        queue.removeMatchingMessages("counter > 2");

        assertEquals("Should have no more messages in the queue: " + queueViewMBeanName, 0, queue.getQueueSize());
        assertEquals("dest has no memory usage", 0, queue.getMemoryPercentUsage());
    }

    public void testCreateDestinationWithSpacesAtEnds() throws Exception {
        ObjectName brokerName = assertRegisteredObjectName(domain + ":Type=Broker,BrokerName=localhost");
        BrokerViewMBean broker = (BrokerViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, brokerName, BrokerViewMBean.class, true);

        assertTrue("broker is not a slave", !broker.isSlave());
        // create 2 topics
        broker.addTopic(getDestinationString() + "1 ");
        broker.addTopic(" " + getDestinationString() + "2");
        broker.addTopic(" " + getDestinationString() + "3 ");

        assertNotRegisteredObjectName(domain + ":Type=Topic,BrokerName=localhost,Destination=" + getDestinationString() + "1 ");
        assertNotRegisteredObjectName(domain + ":Type=Topic,BrokerName=localhost,Destination= " + getDestinationString() + "2");
        assertNotRegisteredObjectName(domain + ":Type=Topic,BrokerName=localhost,Destination= " + getDestinationString() + "3 ");

        ObjectName topicObjName1 = assertRegisteredObjectName(domain + ":Type=Topic,BrokerName=localhost,Destination=" + getDestinationString() + "1");
        ObjectName topicObjName2 = assertRegisteredObjectName(domain + ":Type=Topic,BrokerName=localhost,Destination=" + getDestinationString() + "2");
        ObjectName topicObjName3 = assertRegisteredObjectName(domain + ":Type=Topic,BrokerName=localhost,Destination=" + getDestinationString() + "3");

        TopicViewMBean topic1 = (TopicViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, topicObjName1, TopicViewMBean.class, true);
        TopicViewMBean topic2 = (TopicViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, topicObjName2, TopicViewMBean.class, true);
        TopicViewMBean topic3 = (TopicViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, topicObjName3, TopicViewMBean.class, true);

        assertEquals("topic1 Durable subscriber count", 0, topic1.getConsumerCount());
        assertEquals("topic2 Durable subscriber count", 0, topic2.getConsumerCount());
        assertEquals("topic3 Durable subscriber count", 0, topic3.getConsumerCount());

        String topicName = getDestinationString();
        String selector = null;

        // create 1 subscriber for each topic
        broker.createDurableSubscriber(clientID, "topic1.subscriber1", topicName + "1", selector);
        broker.createDurableSubscriber(clientID, "topic2.subscriber1", topicName + "2", selector);
        broker.createDurableSubscriber(clientID, "topic3.subscriber1", topicName + "3", selector);

        assertEquals("topic1 Durable subscriber count", 1, topic1.getConsumerCount());
        assertEquals("topic2 Durable subscriber count", 1, topic2.getConsumerCount());
        assertEquals("topic3 Durable subscriber count", 1, topic3.getConsumerCount());
    }

    @SuppressWarnings("rawtypes")
    protected void assertSendViaMBean() throws Exception {
        String queueName = getDestinationString() + ".SendMBBean";

        ObjectName brokerName = assertRegisteredObjectName(domain + ":Type=Broker,BrokerName=localhost");
        echo("Create QueueView MBean...");
        BrokerViewMBean broker = (BrokerViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, brokerName, BrokerViewMBean.class, true);
        broker.addQueue(queueName);

        ObjectName queueViewMBeanName = assertRegisteredObjectName(domain + ":Type=Queue,Destination=" + queueName + ",BrokerName=localhost");

        echo("Create QueueView MBean...");
        QueueViewMBean proxy = (QueueViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, queueViewMBeanName, QueueViewMBean.class, true);

        proxy.purge();

        int count = 5;
        for (int i = 0; i < count; i++) {
            String body = "message:" + i;

            Map<String, Object> headers = new HashMap<String, Object>();
            headers.put("JMSCorrelationID", "MyCorrId");
            headers.put("JMSDeliveryMode", Boolean.FALSE);
            headers.put("JMSXGroupID", "MyGroupID");
            headers.put("JMSXGroupSeq", 1234);
            headers.put("JMSPriority", i + 1);
            headers.put("JMSType", "MyType");
            headers.put("MyHeader", i);
            headers.put("MyStringHeader", "StringHeader" + i);

            proxy.sendTextMessage(headers, body);
        }

        CompositeData[] compdatalist = proxy.browse();
        if (compdatalist.length == 0) {
            fail("There is no message in the queue:");
        }

        for (int i = 0; i < compdatalist.length; i++) {
            CompositeData cdata = compdatalist[i];

            if (i == 0) {
                echo("Columns: " + cdata.getCompositeType().keySet());
            }

            assertComplexData(i, cdata, "JMSCorrelationID", "MyCorrId");
            assertComplexData(i, cdata, "JMSPriority", i + 1);
            assertComplexData(i, cdata, "JMSType", "MyType");
            assertComplexData(i, cdata, "JMSCorrelationID", "MyCorrId");
            assertComplexData(i, cdata, "JMSDeliveryMode", "NON-PERSISTENT");
            String expected = "{MyStringHeader=StringHeader" + i + ", MyHeader=" + i + "}";
            // The order of the properties is different when using the ibm jdk.
            if (System.getProperty("java.vendor").equals("IBM Corporation")) {
                expected = "{MyHeader=" + i + ", MyStringHeader=StringHeader" + i + "}";
            }
            assertComplexData(i, cdata, "PropertiesText", expected);

            Map intProperties = CompositeDataHelper.getTabularMap(cdata, CompositeDataConstants.INT_PROPERTIES);
            assertEquals("intProperties size()", 1, intProperties.size());
            assertEquals("intProperties.MyHeader", i, intProperties.get("MyHeader"));

            Map stringProperties = CompositeDataHelper.getTabularMap(cdata, CompositeDataConstants.STRING_PROPERTIES);
            assertEquals("stringProperties size()", 1, stringProperties.size());
            assertEquals("stringProperties.MyHeader", "StringHeader" + i, stringProperties.get("MyStringHeader"));

            Map properties = CompositeDataHelper.getMessageUserProperties(cdata);
            assertEquals("properties size()", 2, properties.size());
            assertEquals("properties.MyHeader", i, properties.get("MyHeader"));
            assertEquals("properties.MyHeader", "StringHeader" + i, properties.get("MyStringHeader"));

            assertComplexData(i, cdata, "JMSXGroupSeq", 1234);
            assertComplexData(i, cdata, "JMSXGroupID", "MyGroupID");
            assertComplexData(i, cdata, "Text", "message:" + i);
        }
    }

    protected void assertComplexData(int messageIndex, CompositeData cdata, String name, Object expected) {
        Object value = cdata.get(name);
        assertEquals("Message " + messageIndex + " CData field: " + name, expected, value);
    }

    protected void assertQueueBrowseWorks() throws Exception {
        Integer mbeancnt = mbeanServer.getMBeanCount();
        echo("Mbean count :" + mbeancnt);

        ObjectName queueViewMBeanName = assertRegisteredObjectName(domain + ":Type=Queue,Destination=" + getDestinationString() + ",BrokerName=localhost");

        echo("Create QueueView MBean...");
        QueueViewMBean proxy = (QueueViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, queueViewMBeanName, QueueViewMBean.class, true);

        long concount = proxy.getConsumerCount();
        echo("Consumer Count :" + concount);
        long messcount = proxy.getQueueSize();
        echo("current number of messages in the queue :" + messcount);

        // lets browse
        CompositeData[] compdatalist = proxy.browse();
        if (compdatalist.length == 0) {
            fail("There is no message in the queue:");
        }
        String[] messageIDs = new String[compdatalist.length];

        for (int i = 0; i < compdatalist.length; i++) {
            CompositeData cdata = compdatalist[i];

            if (i == 0) {
                echo("Columns: " + cdata.getCompositeType().keySet());
            }
            messageIDs[i] = (String)cdata.get("JMSMessageID");
            echo("message " + i + " : " + cdata.values());
        }

        TabularData table = proxy.browseAsTable();
        echo("Found tabular data: " + table);
        assertTrue("Table should not be empty!", table.size() > 0);

        assertEquals("Queue size", MESSAGE_COUNT, proxy.getQueueSize());

        String messageID = messageIDs[0];
        String newDestinationName = "queue://dummy.test.cheese";
        echo("Attempting to copy: " + messageID + " to destination: " + newDestinationName);
        proxy.copyMessageTo(messageID, newDestinationName);

        assertEquals("Queue size", MESSAGE_COUNT, proxy.getQueueSize());

        messageID = messageIDs[1];
        echo("Attempting to remove: " + messageID);
        proxy.removeMessage(messageID);

        assertEquals("Queue size", MESSAGE_COUNT-1, proxy.getQueueSize());

        echo("Worked!");
    }

    protected void assertCreateAndDestroyDurableSubscriptions() throws Exception {
        // lets create a new topic
        ObjectName brokerName = assertRegisteredObjectName(domain + ":Type=Broker,BrokerName=localhost");
        echo("Create QueueView MBean...");
        BrokerViewMBean broker = (BrokerViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, brokerName, BrokerViewMBean.class, true);

        broker.addTopic(getDestinationString());

        assertEquals("Durable subscriber count", 0, broker.getDurableTopicSubscribers().length);

        String topicName = getDestinationString();
        String selector = null;
        ObjectName name1 = broker.createDurableSubscriber(clientID, "subscriber1", topicName, selector);
        broker.createDurableSubscriber(clientID, "subscriber2", topicName, selector);
        assertEquals("Durable subscriber count", 2, broker.getInactiveDurableTopicSubscribers().length);

        assertNotNull("Should have created an mbean name for the durable subscriber!", name1);

        LOG.info("Created durable subscriber with name: " + name1);

        // now lets try destroy it
        broker.destroyDurableSubscriber(clientID, "subscriber1");
        assertEquals("Durable subscriber count", 1, broker.getInactiveDurableTopicSubscribers().length);
    }

    protected void assertConsumerCounts() throws Exception {
        ObjectName brokerName = assertRegisteredObjectName(domain + ":Type=Broker,BrokerName=localhost");
        BrokerViewMBean broker = (BrokerViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, brokerName, BrokerViewMBean.class, true);

        assertTrue("broker is not a slave", !broker.isSlave());
        // create 2 topics
        broker.addTopic(getDestinationString() + "1");
        broker.addTopic(getDestinationString() + "2");

        ObjectName topicObjName1 = assertRegisteredObjectName(domain + ":Type=Topic,BrokerName=localhost,Destination=" + getDestinationString() + "1");
        ObjectName topicObjName2 = assertRegisteredObjectName(domain + ":Type=Topic,BrokerName=localhost,Destination=" + getDestinationString() + "2");
        TopicViewMBean topic1 = (TopicViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, topicObjName1, TopicViewMBean.class, true);
        TopicViewMBean topic2 = (TopicViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, topicObjName2, TopicViewMBean.class, true);

        assertEquals("topic1 Durable subscriber count", 0, topic1.getConsumerCount());
        assertEquals("topic2 Durable subscriber count", 0, topic2.getConsumerCount());

        String topicName = getDestinationString();
        String selector = null;

        // create 1 subscriber for each topic
        broker.createDurableSubscriber(clientID, "topic1.subscriber1", topicName + "1", selector);
        broker.createDurableSubscriber(clientID, "topic2.subscriber1", topicName + "2", selector);

        assertEquals("topic1 Durable subscriber count", 1, topic1.getConsumerCount());
        assertEquals("topic2 Durable subscriber count", 1, topic2.getConsumerCount());

        // create 1 more subscriber for topic1
        broker.createDurableSubscriber(clientID, "topic1.subscriber2", topicName + "1", selector);

        assertEquals("topic1 Durable subscriber count", 2, topic1.getConsumerCount());
        assertEquals("topic2 Durable subscriber count", 1, topic2.getConsumerCount());

        // destroy topic1 subscriber
        broker.destroyDurableSubscriber(clientID, "topic1.subscriber1");

        assertEquals("topic1 Durable subscriber count", 1, topic1.getConsumerCount());
        assertEquals("topic2 Durable subscriber count", 1, topic2.getConsumerCount());

        // destroy topic2 subscriber
        broker.destroyDurableSubscriber(clientID, "topic2.subscriber1");

        assertEquals("topic1 Durable subscriber count", 1, topic1.getConsumerCount());
        assertEquals("topic2 Durable subscriber count", 0, topic2.getConsumerCount());

        // destroy remaining topic1 subscriber
        broker.destroyDurableSubscriber(clientID, "topic1.subscriber2");

        assertEquals("topic1 Durable subscriber count", 0, topic1.getConsumerCount());
        assertEquals("topic2 Durable subscriber count", 0, topic2.getConsumerCount());
    }

    protected void assertProducerCounts() throws Exception {
        ObjectName brokerName = assertRegisteredObjectName(domain + ":Type=Broker,BrokerName=localhost");
        BrokerViewMBean broker = (BrokerViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, brokerName, BrokerViewMBean.class, true);

        assertTrue("broker is not a slave", !broker.isSlave());
        // create 2 topics
        broker.addTopic(getDestinationString() + "1");
        broker.addTopic(getDestinationString() + "2");

        ObjectName topicObjName1 = assertRegisteredObjectName(domain + ":Type=Topic,BrokerName=localhost,Destination=" + getDestinationString() + "1");
        ObjectName topicObjName2 = assertRegisteredObjectName(domain + ":Type=Topic,BrokerName=localhost,Destination=" + getDestinationString() + "2");
        TopicViewMBean topic1 = (TopicViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, topicObjName1, TopicViewMBean.class, true);
        TopicViewMBean topic2 = (TopicViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, topicObjName2, TopicViewMBean.class, true);

        assertEquals("topic1 Producer count", 0, topic1.getProducerCount());
        assertEquals("topic2 Producer count", 0, topic2.getProducerCount());
        assertEquals("broker Topic Producer count", 0, broker.getTopicProducers().length);

        // create 1 producer for each topic
        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Destination dest1 = session.createTopic(getDestinationString() + "1");
        Destination dest2 = session.createTopic(getDestinationString() + "2");
        MessageProducer producer1 = session.createProducer(dest1);
        MessageProducer producer2 = session.createProducer(dest2);
        Thread.sleep(500);

        assertEquals("topic1 Producer count", 1, topic1.getProducerCount());
        assertEquals("topic2 Producer count", 1, topic2.getProducerCount());

        assertEquals("broker Topic Producer count", 2, broker.getTopicProducers().length);

        // create 1 more producer for topic1
        MessageProducer producer3 = session.createProducer(dest1);
        Thread.sleep(500);

        assertEquals("topic1 Producer count", 2, topic1.getProducerCount());
        assertEquals("topic2 Producer count", 1, topic2.getProducerCount());

        assertEquals("broker Topic Producer count", 3, broker.getTopicProducers().length);

        // destroy topic1 producer
        producer1.close();
        Thread.sleep(500);

        assertEquals("topic1 Producer count", 1, topic1.getProducerCount());
        assertEquals("topic2 Producer count", 1, topic2.getProducerCount());

        assertEquals("broker Topic Producer count", 2, broker.getTopicProducers().length);

        // destroy topic2 producer
        producer2.close();
        Thread.sleep(500);

        assertEquals("topic1 Producer count", 1, topic1.getProducerCount());
        assertEquals("topic2 Producer count", 0, topic2.getProducerCount());

        assertEquals("broker Topic Producer count", 1, broker.getTopicProducers().length);

        // destroy remaining topic1 producer
        producer3.close();
        Thread.sleep(500);

        assertEquals("topic1 Producer count", 0, topic1.getProducerCount());
        assertEquals("topic2 Producer count", 0, topic2.getProducerCount());

        MessageProducer producer4 = session.createProducer(null);
        Thread.sleep(500);
        assertEquals(1, broker.getDynamicDestinationProducers().length);
        producer4.close();
        Thread.sleep(500);

        assertEquals("broker Topic Producer count", 0, broker.getTopicProducers().length);
    }

    protected ObjectName assertRegisteredObjectName(String name) throws MalformedObjectNameException, NullPointerException {
        ObjectName objectName = new ObjectName(name);
        if (mbeanServer.isRegistered(objectName)) {
            echo("Bean Registered: " + objectName);
        } else {
            fail("Could not find MBean!: " + objectName);
        }
        return objectName;
    }

    protected ObjectName assertNotRegisteredObjectName(String name) throws MalformedObjectNameException, NullPointerException {
        ObjectName objectName = new ObjectName(name);
        if (mbeanServer.isRegistered(objectName)) {
            fail("Found the MBean!: " + objectName);
        } else {
            echo("Bean not registered Registered: " + objectName);
        }
        return objectName;
    }

    protected void setUp() throws Exception {
        bindAddress = "tcp://localhost:0";
        useTopic = false;
        super.setUp();
        mbeanServer = broker.getManagementContext().getMBeanServer();
    }

    protected void tearDown() throws Exception {
        if (waitForKeyPress) {
            // We are running from the command line so let folks browse the
            // mbeans...
            System.out.println();
            System.out.println("Press enter to terminate the program.");
            System.out.println("In the meantime you can use your JMX console to view the current MBeans");
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            reader.readLine();
        }

        if (connection != null) {
            connection.close();
            connection = null;
        }
        super.tearDown();
    }

    @Override
    protected ConnectionFactory createConnectionFactory() throws Exception {
        return new ActiveMQConnectionFactory(broker.getTransportConnectors().get(0).getPublishableConnectString());
    }

    protected BrokerService createBroker() throws Exception {
        BrokerService answer = new BrokerService();
        answer.setPersistent(false);
        answer.setDeleteAllMessagesOnStartup(true);
        answer.setUseJmx(true);

        // apply memory limit so that %usage is visible
        PolicyMap policyMap = new PolicyMap();
        PolicyEntry defaultEntry = new PolicyEntry();
        defaultEntry.setMemoryLimit(1024*1024*4);
        policyMap.setDefaultEntry(defaultEntry);
        answer.setDestinationPolicy(policyMap);

        answer.addConnector(bindAddress);
        return answer;
    }

    protected void useConnection(Connection connection) throws Exception {
        connection.setClientID(clientID);
        connection.start();
        Session session = connection.createSession(transacted, authMode);
        destination = createDestination();
        MessageProducer producer = session.createProducer(destination);
        for (int i = 0; i < MESSAGE_COUNT; i++) {
            Message message = session.createTextMessage("Message: " + i);
            message.setIntProperty("counter", i);
            message.setJMSCorrelationID("MyCorrelationID");
            message.setJMSReplyTo(new ActiveMQQueue("MyReplyTo"));
            message.setJMSType("MyType");
            message.setJMSPriority(5);
            producer.send(message);
        }
        Thread.sleep(1000);
    }

    protected void useConnectionWithBlobMessage(Connection connection) throws Exception {
        connection.setClientID(clientID);
        connection.start();
        ActiveMQSession session = (ActiveMQSession) connection.createSession(transacted, authMode);
        destination = createDestination();
        MessageProducer producer = session.createProducer(destination);
        for (int i = 0; i < MESSAGE_COUNT; i++) {
            BlobMessage message = session.createBlobMessage(new URL("http://foo.bar/test"));
            message.setIntProperty("counter", i);
            message.setJMSCorrelationID("MyCorrelationID");
            message.setJMSReplyTo(new ActiveMQQueue("MyReplyTo"));
            message.setJMSType("MyType");
            message.setJMSPriority(5);
            producer.send(message);
        }
        Thread.sleep(1000);
    }

    protected void useConnectionWithByteMessage(Connection connection) throws Exception {
        connection.setClientID(clientID);
        connection.start();
        ActiveMQSession session = (ActiveMQSession) connection.createSession(transacted, authMode);
        destination = createDestination();
        MessageProducer producer = session.createProducer(destination);
        for (int i = 0; i < MESSAGE_COUNT; i++) {
            BytesMessage message = session.createBytesMessage();
            message.writeBytes(("Message: " + i).getBytes());
            message.setIntProperty("counter", i);
            message.setJMSCorrelationID("MyCorrelationID");
            message.setJMSReplyTo(new ActiveMQQueue("MyReplyTo"));
            message.setJMSType("MyType");
            message.setJMSPriority(5);
            producer.send(message);
        }
        Thread.sleep(1000);
    }

    protected void echo(String text) {
        LOG.info(text);
    }

    protected String getSecondDestinationString() {
        return "test.new.destination." + getClass() + "." + getName();
    }

    public void testDynamicProducerView() throws Exception {
        connection = connectionFactory.createConnection();

        ObjectName brokerName = assertRegisteredObjectName(domain + ":Type=Broker,BrokerName=localhost");
        BrokerViewMBean broker = (BrokerViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, brokerName, BrokerViewMBean.class, true);

        assertTrue("broker is not a slave", !broker.isSlave());
        assertEquals(0, broker.getDynamicDestinationProducers().length);

        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        MessageProducer producer = session.createProducer(null);

        Destination dest1 = session.createTopic("DynamicDest-1");
        Destination dest2 = session.createTopic("DynamicDest-2");
        Destination dest3 = session.createQueue("DynamicDest-3");

        // Wait a bit to let the producer get registered.
        Thread.sleep(100);

        assertEquals(1, broker.getDynamicDestinationProducers().length);

        ObjectName viewName = broker.getDynamicDestinationProducers()[0];
        assertNotNull(viewName);
        ProducerViewMBean view = (ProducerViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, viewName, ProducerViewMBean.class, true);
        assertNotNull(view);

        assertEquals("NOTSET", view.getDestinationName());

        producer.send(dest1, session.createTextMessage("Test Message 1"));
        Thread.sleep(200);
        assertEquals(((ActiveMQDestination)dest1).getPhysicalName(), view.getDestinationName());
        assertTrue(view.isDestinationTopic());
        assertFalse(view.isDestinationQueue());
        assertFalse(view.isDestinationTemporary());

        producer.send(dest2, session.createTextMessage("Test Message 2"));
        Thread.sleep(200);
        assertEquals(((ActiveMQDestination)dest2).getPhysicalName(), view.getDestinationName());
        assertTrue(view.isDestinationTopic());
        assertFalse(view.isDestinationQueue());
        assertFalse(view.isDestinationTemporary());

        producer.send(dest3, session.createTextMessage("Test Message 3"));
        Thread.sleep(200);
        assertEquals(((ActiveMQDestination)dest3).getPhysicalName(), view.getDestinationName());
        assertTrue(view.isDestinationQueue());
        assertFalse(view.isDestinationTopic());
        assertFalse(view.isDestinationTemporary());

        producer.close();
        Thread.sleep(200);
        assertEquals(0, broker.getDynamicDestinationProducers().length);
    }

    public void testTempQueueJMXDelete() throws Exception {
        connection = connectionFactory.createConnection();

        connection.setClientID(clientID);
        connection.start();
        Session session = connection.createSession(transacted, authMode);
        ActiveMQTempQueue tQueue = (ActiveMQTempQueue) session.createTemporaryQueue();
        Thread.sleep(1000);
        ObjectName queueViewMBeanName = assertRegisteredObjectName(domain + ":Type="+  JMXSupport.encodeObjectNamePart(tQueue.getDestinationTypeAsString())+",Destination=" + JMXSupport.encodeObjectNamePart(tQueue.getPhysicalName()) + ",BrokerName=localhost");

        // should not throw an exception
        mbeanServer.getObjectInstance(queueViewMBeanName);

        tQueue.delete();
        Thread.sleep(1000);
        try {
            // should throw an exception
            mbeanServer.getObjectInstance(queueViewMBeanName);

            fail("should be deleted already!");
        } catch (Exception e) {
            // expected!
        }
    }

    // Test for AMQ-3029
    public void testBrowseBlobMessages() throws Exception {
        connection = connectionFactory.createConnection();
        useConnectionWithBlobMessage(connection);

        ObjectName queueViewMBeanName = assertRegisteredObjectName(domain + ":Type=Queue,Destination=" + getDestinationString() + ",BrokerName=localhost");

        QueueViewMBean queue = (QueueViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, queueViewMBeanName, QueueViewMBean.class, true);

        CompositeData[] compdatalist = queue.browse();
        int initialQueueSize = compdatalist.length;
        if (initialQueueSize == 0) {
            fail("There is no message in the queue:");
        }
        else {
            echo("Current queue size: " + initialQueueSize);
        }
        int messageCount = initialQueueSize;
        String[] messageIDs = new String[messageCount];
        for (int i = 0; i < messageCount; i++) {
            CompositeData cdata = compdatalist[i];
            String messageID = (String) cdata.get("JMSMessageID");
            assertNotNull("Should have a message ID for message " + i, messageID);

            messageIDs[i] = messageID;
        }

        assertTrue("dest has some memory usage", queue.getMemoryPercentUsage() > 0);
    }

    public void testSubscriptionViewToConnectionMBean() throws Exception {

        connection = connectionFactory.createConnection("admin", "admin");
        connection.setClientID("MBeanTest");
        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Destination queue = session.createQueue(getDestinationString() + ".Queue");
        MessageConsumer queueConsumer = session.createConsumer(queue);
        MessageProducer producer = session.createProducer(queue);

        ObjectName brokerName = assertRegisteredObjectName(domain + ":Type=Broker,BrokerName=localhost");
        BrokerViewMBean broker = (BrokerViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, brokerName, BrokerViewMBean.class, true);

        Thread.sleep(100);

        assertTrue(broker.getQueueSubscribers().length == 1);

        ObjectName subscriptionName = broker.getQueueSubscribers()[0];
        LOG.info("Looking for Subscription: " + subscriptionName);

        SubscriptionViewMBean subscriberView =
            (SubscriptionViewMBean)MBeanServerInvocationHandler.newProxyInstance(
                    mbeanServer, subscriptionName, SubscriptionViewMBean.class, true);
        assertNotNull(subscriberView);

        ObjectName connectionName = subscriberView.getConnection();
        LOG.info("Looking for Connection: " + connectionName);
        assertNotNull(connectionName);
        ConnectionViewMBean connectionView =
            (ConnectionViewMBean)MBeanServerInvocationHandler.newProxyInstance(
                    mbeanServer, connectionName, ConnectionViewMBean.class, true);
        assertNotNull(connectionView);

        // Our consumer plus one advisory consumer.
        assertEquals(2, connectionView.getConsumers().length);

        // Check that the subscription view we found earlier is in this list.
        boolean found = false;
        for (ObjectName name : connectionView.getConsumers()) {
            if (name.equals(subscriptionName)) {
                found = true;
            }
        }
        assertTrue("We should have found: " + subscriptionName, found);

        // Our producer and no others.
        assertEquals(1, connectionView.getProducers().length);

        // Bean should detect the updates.
        queueConsumer.close();
        producer.close();

        Thread.sleep(200);

        // Only an advisory consumers now.
        assertEquals(1, connectionView.getConsumers().length);
        assertEquals(0, connectionView.getProducers().length);
    }

    public void testCreateAndUnsubscribeDurableSubscriptions() throws Exception {

        connection = connectionFactory.createConnection("admin", "admin");
        connection.setClientID("MBeanTest");

        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        String topicName = getDestinationString() + ".DurableTopic";
        Topic topic = session.createTopic(topicName);

        ObjectName brokerName = assertRegisteredObjectName(domain + ":Type=Broker,BrokerName=localhost");
        echo("Create QueueView MBean...");
        BrokerViewMBean broker = (BrokerViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, brokerName, BrokerViewMBean.class, true);

        assertEquals("Durable subscriber count", 0, broker.getDurableTopicSubscribers().length);
        assertEquals("Durable subscriber count", 0, broker.getInactiveDurableTopicSubscribers().length);

        MessageConsumer durableConsumer1 = session.createDurableSubscriber(topic, "subscription1");
        MessageConsumer durableConsumer2 = session.createDurableSubscriber(topic, "subscription2");

        Thread.sleep(100);

        assertEquals("Durable subscriber count", 2, broker.getDurableTopicSubscribers().length);
        assertEquals("Durable subscriber count", 0, broker.getInactiveDurableTopicSubscribers().length);

        durableConsumer1.close();
        durableConsumer2.close();

        Thread.sleep(100);

        assertEquals("Durable subscriber count", 0, broker.getDurableTopicSubscribers().length);
        assertEquals("Durable subscriber count", 2, broker.getInactiveDurableTopicSubscribers().length);

        session.unsubscribe("subscription1");

        Thread.sleep(100);

        assertEquals("Inactive Durable subscriber count", 1, broker.getInactiveDurableTopicSubscribers().length);

        session.unsubscribe("subscription2");

        assertEquals("Inactive Durable subscriber count", 0, broker.getInactiveDurableTopicSubscribers().length);
    }

    public void testUserNamePopulated() throws Exception {
        doTestUserNameInMBeans(true);
    }

    public void testUserNameNotPopulated() throws Exception {
        doTestUserNameInMBeans(false);
    }

    @SuppressWarnings("unused")
    private void doTestUserNameInMBeans(boolean expect) throws Exception {
        broker.setPopulateUserNameInMBeans(expect);

        connection = connectionFactory.createConnection("admin", "admin");
        connection.setClientID("MBeanTest");
        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Destination queue = session.createQueue(getDestinationString() + ".Queue");
        Topic topic = session.createTopic(getDestinationString() + ".Topic");
        MessageProducer producer = session.createProducer(queue);
        MessageConsumer queueConsumer = session.createConsumer(queue);
        MessageConsumer topicConsumer = session.createConsumer(topic);
        MessageConsumer durable = session.createDurableSubscriber(topic, "Durable");

        ObjectName brokerName = assertRegisteredObjectName(domain + ":Type=Broker,BrokerName=localhost");
        BrokerViewMBean broker = (BrokerViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, brokerName, BrokerViewMBean.class, true);

        Thread.sleep(100);

        assertTrue(broker.getQueueProducers().length == 1);
        assertTrue(broker.getTopicSubscribers().length == 2);
        assertTrue(broker.getQueueSubscribers().length == 1);

        ObjectName producerName = broker.getQueueProducers()[0];
        ProducerViewMBean producerView = (ProducerViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, producerName, ProducerViewMBean.class, true);
        assertNotNull(producerView);

        if (expect) {
            assertEquals("admin", producerView.getUserName());
        } else {
            assertNull(producerView.getUserName());
        }

        for (ObjectName name : broker.getTopicSubscribers()) {
            SubscriptionViewMBean subscriberView = (SubscriptionViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, name, SubscriptionViewMBean.class, true);
            if (expect) {
                assertEquals("admin", subscriberView.getUserName());
            } else {
                assertNull(subscriberView.getUserName());
            }
        }

        for (ObjectName name : broker.getQueueSubscribers()) {
            SubscriptionViewMBean subscriberView = (SubscriptionViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, name, SubscriptionViewMBean.class, true);
            if (expect) {
                assertEquals("admin", subscriberView.getUserName());
            } else {
                assertNull(subscriberView.getUserName());
            }
        }

        Set<ObjectName> names = mbeanServer.queryNames(null, null);
        boolean found = false;
        for (ObjectName name : names) {
            if (name.toString().startsWith(domain + ":BrokerName=localhost,Type=Connection,ConnectorName=tcp") &&
                name.toString().endsWith("Connection=MBeanTest")) {

                ConnectionViewMBean connectionView =
                    (ConnectionViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, name, ConnectionViewMBean.class, true);
                assertNotNull(connectionView);

                if (expect) {
                    assertEquals("admin", connectionView.getUserName());
                } else {
                    assertNull(connectionView.getUserName());
                }

                found = true;
                break;
            }
        }

        assertTrue("Should find the connection's ManagedTransportConnection", found);
    }

    public void testMoveMessagesToRetainOrder() throws Exception {
        connection = connectionFactory.createConnection();
        useConnection(connection);

        ObjectName queueViewMBeanName = assertRegisteredObjectName(domain + ":Type=Queue,Destination=" + getDestinationString() + ",BrokerName=localhost");

        QueueViewMBean queue = (QueueViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, queueViewMBeanName, QueueViewMBean.class, true);

        String newDestination = getSecondDestinationString();
        queue.moveMatchingMessagesTo("", newDestination);

        queueViewMBeanName = assertRegisteredObjectName(domain + ":Type=Queue,Destination=" + newDestination + ",BrokerName=localhost");

        queue = (QueueViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, queueViewMBeanName, QueueViewMBean.class, true);
        int movedSize = MESSAGE_COUNT;
        assertEquals("Unexpected number of messages ",movedSize,queue.getQueueSize());

        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Destination destination = session.createQueue(newDestination);
        MessageConsumer consumer = session.createConsumer(destination);

        int last = -1;
        int current = -1;
        Message message = null;
        while ((message = consumer.receive(2000)) != null) {
            if (message.propertyExists("counter")) {
                current = message.getIntProperty("counter");
                assertEquals(last, current - 1);
                last = current;
            }
        }

        // now lets remove them by selector
        queue.removeMatchingMessages("");

        assertEquals("Should have no more messages in the queue: " + queueViewMBeanName, 0, queue.getQueueSize());
        assertEquals("dest has no memory usage", 0, queue.getMemoryPercentUsage());
    }

    public void testCopyMessagesToRetainOrder() throws Exception {
        connection = connectionFactory.createConnection();
        useConnection(connection);

        ObjectName queueViewMBeanName = assertRegisteredObjectName(domain + ":Type=Queue,Destination=" + getDestinationString() + ",BrokerName=localhost");

        QueueViewMBean queue = (QueueViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, queueViewMBeanName, QueueViewMBean.class, true);

        String newDestination = getSecondDestinationString();
        queue.copyMatchingMessagesTo("", newDestination);

        queueViewMBeanName = assertRegisteredObjectName(domain + ":Type=Queue,Destination=" + newDestination + ",BrokerName=localhost");

        queue = (QueueViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, queueViewMBeanName, QueueViewMBean.class, true);
        int movedSize = MESSAGE_COUNT;
        assertEquals("Unexpected number of messages ",movedSize,queue.getQueueSize());

        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Destination destination = session.createQueue(newDestination);
        MessageConsumer consumer = session.createConsumer(destination);

        int last = -1;
        int current = -1;
        Message message = null;
        while ((message = consumer.receive(2000)) != null) {
            if (message.propertyExists("counter")) {
                current = message.getIntProperty("counter");
                assertEquals(last, current - 1);
                last = current;
            }
        }

        // now lets remove them by selector
        queue.removeMatchingMessages("");

        assertEquals("Should have no more messages in the queue: " + queueViewMBeanName, 0, queue.getQueueSize());
        assertEquals("dest has no memory usage", 0, queue.getMemoryPercentUsage());
    }

    public void testRemoveMatchingMessageRetainOrder() throws Exception {
        connection = connectionFactory.createConnection();
        useConnection(connection);

        ObjectName queueViewMBeanName = assertRegisteredObjectName(domain + ":Type=Queue,Destination=" + getDestinationString() + ",BrokerName=localhost");

        QueueViewMBean queue = (QueueViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, queueViewMBeanName, QueueViewMBean.class, true);

        String queueName = getDestinationString();
        queue.removeMatchingMessages("counter < 10");

        int newSize = MESSAGE_COUNT - 10;
        assertEquals("Unexpected number of messages ", newSize, queue.getQueueSize());

        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Destination destination = session.createQueue(queueName);
        MessageConsumer consumer = session.createConsumer(destination);

        int last = 9;
        int current = 0;
        Message message = null;
        while ((message = consumer.receive(2000)) != null) {
            if (message.propertyExists("counter")) {
                current = message.getIntProperty("counter");
                assertEquals(last, current - 1);
                last = current;
            }
        }

        // now lets remove them by selector
        queue.removeMatchingMessages("");

        assertEquals("Should have no more messages in the queue: " + queueViewMBeanName, 0, queue.getQueueSize());
        assertEquals("dest has no memory usage", 0, queue.getMemoryPercentUsage());
    }

    public void testBrowseBytesMessages() throws Exception {
        connection = connectionFactory.createConnection();
        useConnectionWithByteMessage(connection);

        ObjectName queueViewMBeanName = assertRegisteredObjectName(domain + ":Type=Queue,Destination=" + getDestinationString() + ",BrokerName=localhost");

        QueueViewMBean queue = (QueueViewMBean)MBeanServerInvocationHandler.newProxyInstance(mbeanServer, queueViewMBeanName, QueueViewMBean.class, true);

        CompositeData[] compdatalist = queue.browse();
        int initialQueueSize = compdatalist.length;
        if (initialQueueSize == 0) {
            fail("There is no message in the queue:");
        }
        else {
            echo("Current queue size: " + initialQueueSize);
        }
        int messageCount = initialQueueSize;
        String[] messageIDs = new String[messageCount];
        for (int i = 0; i < messageCount; i++) {
            CompositeData cdata = compdatalist[i];
            String messageID = (String) cdata.get("JMSMessageID");
            assertNotNull("Should have a message ID for message " + i, messageID);
            messageIDs[i] = messageID;

            Byte[] preview = (Byte[]) cdata.get(CompositeDataConstants.BODY_PREVIEW);
            assertNotNull("should be a preview", preview);
            assertTrue("not empty", preview.length > 0);
        }

        assertTrue("dest has some memory usage", queue.getMemoryPercentUsage() > 0);

        // consume all the messages
        echo("Attempting to consume all bytes messages from: " + destination);
        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        MessageConsumer consumer = session.createConsumer(destination);
        for (int i=0; i<MESSAGE_COUNT; i++) {
            Message message = consumer.receive(5000);
            assertNotNull(message);
            assertTrue(message instanceof BytesMessage);
        }
        consumer.close();
        session.close();
    }
}
