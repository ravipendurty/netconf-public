/*
 * Copyright (c) 2023 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.restconf.server.spi;

import static java.util.Objects.requireNonNull;

import com.google.common.base.VerifyException;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ListMultimap;
import java.time.Instant;
import java.util.List;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.opendaylight.mdsal.dom.api.DOMNotification;
import org.opendaylight.netconf.common.mdsal.DOMNotificationEvent;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.NodeIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.ContainerNode;
import org.opendaylight.yangtools.yang.data.spi.node.ImmutableNodes;
import org.opendaylight.yangtools.yang.data.tree.api.DataTreeCandidate;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A set of subscribers attached to an {@link RestconfStream}. This is an immutable structure, which can be updated
 * through a copy-on-writer process driven by {@link #add(Subscriber)} and {@link #remove(Subscriber)}.
 *
 * @param <T> event type
 */
abstract sealed class Subscribers<T> {
    static final class Empty<T> extends Subscribers<T> {
        private static final @NonNull Empty<?> INSTANCE = new Empty<>();

        private Empty() {
            // Hidden on purpose
        }

        @Override
        Subscribers<T> add(final Subscriber<T> toAdd) {
            return new Single<>(toAdd);
        }

        @Override
        Subscribers<T> remove(final Subscriber<?> toRemove) {
            return this;
        }

        @Override
        void endOfStream() {
            // No-op
        }

        @Override
        void publish(final EffectiveModelContext modelContext, final T input, final Instant now) {
            // No-op
        }
    }

    private static final class Single<T> extends Subscribers<T> {
        private final Subscriber<T> subscriber;

        Single(final Subscriber<T> subscriber) {
            this.subscriber = requireNonNull(subscriber);
        }

        @Override
        Subscribers<T> add(final Subscriber<T> toAdd) {
            return new Multiple<>(ImmutableListMultimap.of(
                subscriber.formatter(), subscriber,
                toAdd.formatter(), toAdd));
        }

        @Override
        Subscribers<T> remove(final Subscriber<?> toRemove) {
            return toRemove.equals(subscriber) ? null : this;
        }

        @Override
        void endOfStream() {
            subscriber.endOfStream();
        }

        @Override
        void publish(final EffectiveModelContext modelContext, final T input, final Instant now) {
            if (subscriber instanceof Subscriber.Rfc8639Subscriber<T> rfc8639Subscriber
                    && rfc8639Subscriber.eventStreamFilter() != null) {
                subscriber.sendDataMessage(applyStreamFilter(rfc8639Subscriber, modelContext, input, now));
            } else {
                subscriber.sendDataMessage(subscriber.filter().matches(modelContext, input)
                    ? format(subscriber.formatter(), modelContext, input, now) : null);
            }
        }
    }

    private static <T> @Nullable String applyStreamFilter(final Subscriber.Rfc8639Subscriber<T> subscriber,
            final EffectiveModelContext ctx, final T event, final Instant now) {
        // Extract body for subtree evaluation
        final ContainerNode body;
        final YangInstanceIdentifier path;
        switch (event) {
            case DOMNotificationEvent.Rfc7950 n7950 -> {
                body = n7950.getBody();
                path = n7950.path();
            }
            case DOMNotificationEvent.Rfc6020 n6020 -> {
                body = n6020.getBody();
                path = YangInstanceIdentifier.of();
            }
            case DOMNotification notification -> {
                // notification comes from AbstractNotificationSource
                body = notification.getBody();
                path = YangInstanceIdentifier.of();
            }
            case List<?> list when !list.isEmpty() && list.getFirst() instanceof DataTreeCandidate -> {
                // notification comes from DataTreeChangeSource
                body = parseDataTreeCandidates((List<DataTreeCandidate>) list);
                path = YangInstanceIdentifier.of();
            }
            case null, default -> {
                // unknown payload type → treat as filtered out
                return null;
            }
        }
        return subscriber.eventStreamFilter().test(path, body) ? format(subscriber.formatter(), ctx, event, now)
            : null;
    }

    private static ContainerNode parseDataTreeCandidates(final List<DataTreeCandidate> candidates) {
        final var firstArg = candidates.getFirst().getRootPath().getLastPathArgument();
        final var rootId = firstArg instanceof NodeIdentifier nip ? nip : NodeIdentifier.create(firstArg.getNodeType());
        final var builder = ImmutableNodes.newContainerBuilder().withNodeIdentifier(rootId);

        for (final DataTreeCandidate cand : candidates) {
            builder.withChild((ContainerNode) cand.getRootNode().dataAfter());
        }
        return builder.build();
    }

    private static final class Multiple<T> extends Subscribers<T> {
        private final ImmutableListMultimap<EventFormatter<T>, Subscriber<T>> subscribers;

        Multiple(final ListMultimap<EventFormatter<T>, Subscriber<T>> subscribers) {
            this.subscribers = ImmutableListMultimap.copyOf(subscribers);
        }

        @Override
        Subscribers<T> add(final Subscriber<T> toAdd) {
            final var newSubscribers = ArrayListMultimap.create(subscribers);
            newSubscribers.put(toAdd.formatter(), toAdd);
            return new Multiple<>(newSubscribers);
        }

        @Override
        Subscribers<T> remove(final Subscriber<?> toRemove) {
            final var newSubscribers = ArrayListMultimap.create(subscribers);
            return newSubscribers.remove(toRemove.formatter(), toRemove) ? switch (newSubscribers.size()) {
                case 0 -> throw new VerifyException("Unexpected empty subscribers");
                case 1 -> new Single<>(newSubscribers.values().iterator().next());
                default -> new Multiple<>(newSubscribers);
            } : this;
        }

        @Override
        void endOfStream() {
            subscribers.forEach((formatter, subscriber) -> subscriber.endOfStream());
        }

        @Override
        void publish(final EffectiveModelContext modelContext, final T input, final Instant now) {
            for (var entry : subscribers.asMap().entrySet()) {
                final var formatted = format(entry.getKey(), modelContext, input, now);
                for (var subscriber : entry.getValue()) {
                    if (subscriber instanceof Subscriber.Rfc8639Subscriber<T> rfc8639Subscriber
                            && rfc8639Subscriber.eventStreamFilter() != null) {
                        subscriber.sendDataMessage(applyStreamFilter(rfc8639Subscriber, modelContext, input, now));
                    } else {
                        subscriber.sendDataMessage(subscriber.filter().matches(modelContext, input)
                            ? formatted : null);
                    }
                }
            }
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(Subscribers.class);

    private Subscribers() {
        // Hidden on purpose
    }

    /**
     * Return an empty set of subscribers. This is the initial state of {@link RestconfStream}, waiting for the first
     * subscriber to appear.
     *
     * @param <T> event type
     * @return An empty {@link Subscribers} file
     */
    @SuppressWarnings("unchecked")
    static <T> Subscribers<T> empty() {
        return (Subscribers<T>) Empty.INSTANCE;
    }

    /**
     * Add a new subscriber to this file.
     *
     * @param toAdd subscriber to add
     * @return A new {@link Subscribers} file
     * @throws NullPointerException if {@code toAdd} is {@code null}
     */
    abstract @NonNull Subscribers<T> add(Subscriber<T> toAdd);

    /**
     * Remove a subscriber to this file.
     *
     * @param toRemove subscriber to add
     * @return A new {@link Subscribers} file, or {@code null} if this file was not empty and it became empty
     * @throws NullPointerException if {@code toRemove} is {@code null}
     */
    abstract @Nullable Subscribers<T> remove(Subscriber<?> toRemove);

    /**
     * Signal end-of-stream to all subscribers.
     */
    abstract void endOfStream();

    /**
     * Publish an event to all {@link Subscriber}s in this file.
     *
     * @param modelContext An {@link EffectiveModelContext} used to format the input
     * @param input Input data
     * @param now Current time
     * @throws NullPointerException if any argument is {@code null}
     */
    @NonNullByDefault
    abstract void publish(EffectiveModelContext modelContext, T input, Instant now);

    @SuppressWarnings("checkstyle:illegalCatch")
    private static <T> @Nullable String format(final EventFormatter<T> formatter,
            final EffectiveModelContext modelContext, final T input, final Instant now) {
        try {
            return formatter.eventData(modelContext, input, now);
        } catch (Exception e) {
            LOG.warn("Failed to format", e);
            return null;
        }
    }
}
