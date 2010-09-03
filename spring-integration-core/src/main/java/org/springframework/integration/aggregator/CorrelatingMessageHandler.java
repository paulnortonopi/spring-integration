/*
 * Copyright 2002-2010 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package org.springframework.integration.aggregator;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.integration.Message;
import org.springframework.integration.MessageChannel;
import org.springframework.integration.MessageHeaders;
import org.springframework.integration.MessagingException;
import org.springframework.integration.channel.NullChannel;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.core.MessagingTemplate;
import org.springframework.integration.handler.AbstractMessageHandler;
import org.springframework.integration.store.MessageGroup;
import org.springframework.integration.store.MessageGroupCallback;
import org.springframework.integration.store.MessageGroupStore;
import org.springframework.integration.store.MessageStore;
import org.springframework.integration.store.SimpleMessageStore;
import org.springframework.util.Assert;

/**
 * Message handler that holds a buffer of correlated messages in a
 * {@link MessageStore}. This class takes care of correlated groups of messages
 * that can be completed in batches. It is useful for aggregating, resequencing,
 * or custom implementations requiring correlation.
 * <p/>
 * To customize this handler inject {@link CorrelationStrategy},
 * {@link ReleaseStrategy}, and {@link MessageGroupProcessor} implementations as
 * you require.
 * <p/>
 * By default the CorrelationStrategy will be a
 * HeaderAttributeCorrelationStrategy and the ReleaseStrategy will be a
 * SequenceSizeReleaseStrategy.
 * 
 * @author Iwein Fuld
 * @author Dave Syer
 * @since 2.0
 */
public class CorrelatingMessageHandler extends AbstractMessageHandler implements MessageProducer {

	private static final Log logger = LogFactory.getLog(CorrelatingMessageHandler.class);

	public static final long DEFAULT_SEND_TIMEOUT = 1000L;

	public static final long DEFAULT_REAPER_INTERVAL = 1000L;

	public static final long DEFAULT_TIMEOUT = 60000L;


	private MessageGroupStore messageStore;

	private final MessageGroupProcessor outputProcessor;

	private volatile CorrelationStrategy correlationStrategy;

	private volatile ReleaseStrategy releaseStrategy;

	private MessageChannel outputChannel;

	private final MessagingTemplate messagingTemplate = new MessagingTemplate();

	private volatile MessageChannel discardChannel = new NullChannel();

	private boolean sendPartialResultOnExpiry = false;

	private final ConcurrentMap<Object, Object> locks = new ConcurrentHashMap<Object, Object>();


	public CorrelatingMessageHandler(MessageGroupProcessor processor, MessageGroupStore store,
			CorrelationStrategy correlationStrategy, ReleaseStrategy releaseStrategy) {
		Assert.notNull(processor);
		Assert.notNull(store);
		setMessageStore(store);
		this.outputProcessor = processor;
		this.correlationStrategy = correlationStrategy == null ?
				new HeaderAttributeCorrelationStrategy(MessageHeaders.CORRELATION_ID) : correlationStrategy;
		this.releaseStrategy = releaseStrategy == null ? new SequenceSizeReleaseStrategy() : releaseStrategy;
		this.messagingTemplate.setSendTimeout(DEFAULT_SEND_TIMEOUT);
	}

	public CorrelatingMessageHandler(MessageGroupProcessor processor, MessageGroupStore store) {
		this(processor, store, null, null);
	}

	public CorrelatingMessageHandler(MessageGroupProcessor processor) {
		this(processor, new SimpleMessageStore(0), null, null);
	}


	public void setMessageStore(MessageGroupStore store) {
		this.messageStore = store;
		store.registerMessageGroupExpiryCallback(new MessageGroupCallback() {
			public void execute(MessageGroupStore messageGroupStore, MessageGroup group) {
				forceComplete(group);
			}
		});
	}

	public void setCorrelationStrategy(CorrelationStrategy correlationStrategy) {
		Assert.notNull(correlationStrategy);
		this.correlationStrategy = correlationStrategy;
	}

	public void setReleaseStrategy(ReleaseStrategy releaseStrategy) {
		Assert.notNull(releaseStrategy);
		this.releaseStrategy = releaseStrategy;
	}

	public void setOutputChannel(MessageChannel outputChannel) {
		Assert.notNull(outputChannel, "'outputChannel' must not be null");
		this.outputChannel = outputChannel;
	}

	@Override
	protected void onInit() throws Exception {
		super.onInit();
		BeanFactory beanFactory = this.getBeanFactory();
		if (beanFactory != null) {
			this.messagingTemplate.setBeanFactory(beanFactory);
		}
	}

	public void setDiscardChannel(MessageChannel discardChannel) {
		this.discardChannel = discardChannel;
	}

	public void setSendTimeout(long sendTimeout) {
		this.messagingTemplate.setSendTimeout(sendTimeout);
	}

	public void setSendPartialResultOnExpiry(boolean sendPartialResultOnExpiry) {
		this.sendPartialResultOnExpiry = sendPartialResultOnExpiry;
	}

	@Override
	public String getComponentType() {
		return "aggregator";
	}

	@Override
	protected void handleMessageInternal(Message<?> message) throws Exception {
		Object correlationKey = correlationStrategy.getCorrelationKey(message);
		if (logger.isDebugEnabled()) {
			logger.debug("Handling message with correlationKey ["
					+ correlationKey + "]: " + message);
		}
		if (correlationKey==null) {
			throw new IllegalStateException("Null correlation not allowed.  Maybe the CorrelationStrategy is failing?");
		}

		// TODO: INT-1117 - make the lock global?
		Object lock = getLock(correlationKey);
		synchronized (lock) {
			MessageGroup group = messageStore.getMessageGroup(correlationKey);
			if (group.canAdd(message)) {
				group = store(correlationKey, message);
				if (releaseStrategy.canRelease(group)) {
					if (logger.isDebugEnabled()) {
						logger.debug("Completing group with correlationKey ["
								+ correlationKey + "]");
					}
					try {
						Object result = outputProcessor.processMessageGroup(group);
						this.sendReplies(result, message.getHeaders().getReplyChannel());
					}
					finally {
						// Always clean up even if there was an exception
						// processing messages
						if (group.isComplete() || group.getSequenceSize() == 0) {
							// The group is complete or else there is no
							// sequence so there is no more state to track
							remove(group);
						}
						else {
							// Mark these messages as processed, but do not
							// remove the group from store
							mark(group);
						}
					}
				}
				else if (group.isComplete()) {
					try {
						// If not releasing any messages the group might still
						// be complete
						for (Message<?> discard : group.getUnmarked()) {
							discardChannel.send(discard);
						}
					}
					finally {
						remove(group);
					}
				}
			}
			else {
				discardChannel.send(message);
			}
		}
	}

	private final boolean forceComplete(MessageGroup group) {

		Object correlationKey = group.getGroupId();
		Object lock = getLock(correlationKey);
		synchronized (lock) {

			if (group.size() > 0) {
				// last chance for normal completion
				try {
					if (releaseStrategy.canRelease(group)) {
						Object result = outputProcessor.processMessageGroup(group);
						this.sendRepliesForGroup(result, group);
					}
					else {
						if (sendPartialResultOnExpiry) {
							if (logger.isInfoEnabled()) {
								logger.info("Processing partially complete messages for key ["
										+ correlationKey + "] to: " + outputChannel);
							}
							Object result = outputProcessor.processMessageGroup(group);
							this.sendRepliesForGroup(result, group);
						}
						else {
							if (logger.isInfoEnabled()) {
								logger.info("Discarding partially complete messages for key ["
										+ correlationKey + "] to: " + discardChannel);
							}
							for (Message<?> message : group.getUnmarked()) {
								discardChannel.send(message);
							}
						}
					}
				}
				finally {
					remove(group);
				}
				return true;
			}
			return false;
		}
	}

	private Object getLock(Object correlationKey) {
		locks.putIfAbsent(correlationKey, correlationKey);
		return locks.get(correlationKey);
	}

	private void mark(MessageGroup group) {
		messageStore.markMessageGroup(group);
	}

	private void remove(MessageGroup group) {
		Object correlationKey = group.getGroupId();
		messageStore.removeMessageGroup(correlationKey);
		locks.remove(correlationKey);
	}

	private MessageGroup store(Object correlationKey, Message<?> message) {
		return messageStore.addMessageToGroup(correlationKey, message);
	}

	private void sendRepliesForGroup(Object processorResult, MessageGroup group) {
		Object replyChannelHeader = null;
		if (group != null) {
			Message<?> first = group.getOne();
			if (first != null) {
				replyChannelHeader = first.getHeaders().getReplyChannel();
			}
		}
		this.sendReplies(processorResult, replyChannelHeader);
	}

	private void sendReplies(Object processorResult, Object replyChannelHeader) {
		Object replyChannel = this.outputChannel;
		if (this.outputChannel == null) {
			replyChannel = replyChannelHeader;
		}
		Assert.notNull(replyChannel, "no outputChannel or replyChannel header available");
		if (processorResult instanceof Iterable<?> && shouldSendMultipleReplies((Iterable<?>) processorResult)) {
			for (Object next : (Iterable<?>) processorResult) {
				this.sendReplyMessage(next, replyChannel);
			}
		}
		else {
			this.sendReplyMessage(processorResult, replyChannel);
		}
	}

	private void sendReplyMessage(Object reply, Object replyChannel) {
		if (replyChannel instanceof MessageChannel) {
			if (reply instanceof Message<?>) {
				this.messagingTemplate.send((MessageChannel) replyChannel, (Message<?>) reply);
			}
			else {
				this.messagingTemplate.convertAndSend((MessageChannel) replyChannel, reply);
			}
		}
		else if (replyChannel instanceof String) {
			if (reply instanceof Message<?>) {
				this.messagingTemplate.send((String) replyChannel, (Message<?>) reply);
			}
			else {
				this.messagingTemplate.convertAndSend((String) replyChannel, reply);
			}
		}
		else {
			throw new MessagingException("replyChannel must be a MessageChannel or String");
		}
	}

	private boolean shouldSendMultipleReplies(Iterable<?> iter) {
		for (Object next : iter) {
			if (next instanceof Message<?>) {
				return true;
			}
		}
		return false;
	}

}
