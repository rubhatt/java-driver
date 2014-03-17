/*
 *      Copyright (C) 2012 DataStax Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.datastax.driver.core.policies;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.AbstractIterator;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Host;
import com.datastax.driver.core.HostDistance;
import com.datastax.driver.core.Query;

/**
 * A data-center aware Round-robin load balancing policy.
 * <p>
 * This policy provides round-robin queries over the node of the local
 * datacenter. It also includes in the query plans returned a configurable
 * number of hosts in the remote datacenters, but those are always tried
 * after the local nodes. In other words, this policy guarantees that no
 * host in a remote datacenter will be queried unless no host in the local
 * datacenter can be reached.
 * <p>
 * If used with a single datacenter, this policy is equivalent to the
 * {@code LoadBalancingPolicy.RoundRobin} policy, but its DC awareness
 * incurs a slight overhead so the {@code LoadBalancingPolicy.RoundRobin}
 * policy could be prefered to this policy in that case.
 */
public class DCAwareRoundRobinPolicy implements LoadBalancingPolicy {

    private final ConcurrentMap<String, CopyOnWriteArrayList<Host>> perDcLiveHosts = new ConcurrentHashMap<String, CopyOnWriteArrayList<Host>>();
    private final AtomicInteger index = new AtomicInteger();
    private final String localDc;
    private final int usedHostsPerRemoteDc;
    private final boolean dontHopForLocalCL;

    /**
     * Creates a new datacenter aware round robin policy given the name of
     * the local datacenter.
     * <p>
     * The name of the local datacenter provided must be the local
     * datacenter name as known by Cassandra.
     * <p>
     * The policy created will ignore all remote hosts. In other words,
     * this is equivalent to {@code new DCAwareRoundRobinPolicy(localDc, 0)}.
     *
     * @param localDc the name of the local datacenter (as known by
     * Cassandra).
     */
    public DCAwareRoundRobinPolicy(String localDc) {
        this(localDc, 0);
    }

    /**
     * Creates a new DCAwareRoundRobin policy given the name of the local
     * datacenter and that uses the provided number of host per remote
     * datacenter as failover for the local hosts.
     * <p>
     * The name of the local datacenter provided must be the local
     * datacenter name as known by Cassandra.
     * <p>
     * If {@code usedHostsPerRemoteDc > 0}, then if for a query no host
     * in the local datacenter can be reached and if the consistency
     * level of the query is not {@code LOCAL_ONE} or {@code LOCAL_QUORUM},
     * then up to {@code usedHostsPerRemoteDc} host per remote data-center
     * will be tried by the policy as a fallback. Please note that no
     * remote host will be used for {@code LOCAL_ONE} and {@code LOCAL_QUORUM}
     * since this would change the meaning of the consistency level (and
     * thus somewhat break the consistency contract).
     *
     * @param localDc the name of the local datacenter (as known by
     * Cassandra).
     * @param usedHostsPerRemoteDc the number of host per remote
     * datacenter that policies created by the returned factory should
     * consider. Created policies {@code distance} method will return a
     * {@code HostDistance.REMOTE} distance for only {@code
     * usedHostsPerRemoteDc} hosts per remote datacenter. Other hosts
     * of the remote datacenters will be ignored (and thus no
     * connections to them will be maintained).
     */
    public DCAwareRoundRobinPolicy(String localDc, int usedHostsPerRemoteDc) {
        this(localDc, usedHostsPerRemoteDc, false);
    }

    /**
     * Creates a new DCAwareRoundRobin policy given the name of the local
     * datacenter and that uses the provided number of host per remote
     * datacenter as failover for the local hosts.
     * <p>
     * This constructor is equivalent to {@link DCAwareRoundRobinPolicy(String, int)}
     * but allows to override the policy of never using remote data-center
     * nodes for {@code LOCAL_ONE} and {@code LOCAL_QUORUM} queries. It is
     * however inadvisable to do so in almost all cases, as this would
     * potentially break consistency guarantees and if you are fine with that,
     * it's probably better to use a weaker consitency like {@code ONE}, {@code
     * TWO} or {@code THREE}. As such, this constructor should generally
     * be avoided in favor of {@link DCAwareRoundRobinPolicy(String, int)}.
     * Use it only if you know and understand what you do.
     *
     * @param localDc the name of the local datacenter (as known by
     * Cassandra).
     * @param usedHostsPerRemoteDc the number of host per remote
     * datacenter that policies created by the returned factory should
     * consider. Created policies {@code distance} method will return a
     * {@code HostDistance.REMOTE} distance for only {@code
     * usedHostsPerRemoteDc} hosts per remote datacenter. Other hosts
     * of the remote datacenters will be ignored (and thus no
     * connections to them will be maintained).
     * @param allowRemoteDCsForLocalConsistencyLevel whether or not the
     * policy may return remote host when building query plan for query
     * having consitency {@code LOCAL_ONE} and {@code LOCAL_QUORUM}.
     */
    public DCAwareRoundRobinPolicy(String localDc, int usedHostsPerRemoteDc, boolean allowRemoteDCsForLocalConsistencyLevel) {
        this.localDc = localDc;
        this.usedHostsPerRemoteDc = usedHostsPerRemoteDc;
        this.dontHopForLocalCL = !allowRemoteDCsForLocalConsistencyLevel;
    }

    @Override
    public void init(Cluster cluster, Collection<Host> hosts) {
        this.index.set(new Random().nextInt(Math.max(hosts.size(), 1)));

        for (Host host : hosts) {
            String dc = dc(host);
            CopyOnWriteArrayList<Host> prev = perDcLiveHosts.get(dc);
            if (prev == null)
                perDcLiveHosts.put(dc, new CopyOnWriteArrayList<Host>(Collections.singletonList(host)));
            else
                prev.addIfAbsent(host);
        }
    }

    private String dc(Host host) {
        String dc = host.getDatacenter();
        return dc == null ? localDc : dc;
    }

    @SuppressWarnings("unchecked")
    private static CopyOnWriteArrayList<Host> cloneList(CopyOnWriteArrayList<Host> list) {
        return (CopyOnWriteArrayList<Host>)list.clone();
    }

    /**
     * Return the HostDistance for the provided host.
     * <p>
     * This policy consider nodes in the local datacenter as {@code LOCAL}.
     * For each remote datacenter, it considers a configurable number of
     * hosts as {@code REMOTE} and the rest is {@code IGNORED}.
     * <p>
     * To configure how many host in each remote datacenter is considered
     * {@code REMOTE}, see {@link #DCAwareRoundRobinPolicy(String, int)}.
     *
     * @param host the host of which to return the distance of.
     * @return the HostDistance to {@code host}.
     */
    @Override
    public HostDistance distance(Host host) {
        String dc = dc(host);
        if (dc.equals(localDc))
            return HostDistance.LOCAL;

        CopyOnWriteArrayList<Host> dcHosts = perDcLiveHosts.get(dc);
        if (dcHosts == null || usedHostsPerRemoteDc == 0)
            return HostDistance.IGNORED;

        // We need to clone, otherwise our subList call is not thread safe
        dcHosts = cloneList(dcHosts);
        return dcHosts.subList(0, Math.min(dcHosts.size(), usedHostsPerRemoteDc)).contains(host)
             ? HostDistance.REMOTE
             : HostDistance.IGNORED;
    }

    /**
     * Returns the hosts to use for a new query.
     * <p>
     * The returned plan will always try each known host in the local
     * datacenter first, and then, if none of the local host is reachable,
     * will try up to a configurable number of other host per remote datacenter.
     * The order of the local node in the returned query plan will follow a
     * Round-robin algorithm.
     *
     * @param query the query for which to build the plan.
     * @return a new query plan, i.e. an iterator indicating which host to
     * try first for querying, which one to use as failover, etc...
     */
    @Override
    public Iterator<Host> newQueryPlan(final Query query) {

        CopyOnWriteArrayList<Host> localLiveHosts = perDcLiveHosts.get(localDc);
        final List<Host> hosts = localLiveHosts == null ? Collections.<Host>emptyList() : cloneList(localLiveHosts);
        final int startIdx = index.getAndIncrement();

        return new AbstractIterator<Host>() {

            private int idx = startIdx;
            private int remainingLocal = hosts.size();

            // For remote Dcs
            private Iterator<String> remoteDcs;
            private List<Host> currentDcHosts;
            private int currentDcRemaining;

            @Override
            protected Host computeNext() {
                if (remainingLocal > 0) {
                    remainingLocal--;
                    int c = idx++ % hosts.size();
                    if (c < 0)
                        c += hosts.size();
                    return hosts.get(c);
                }

                if (currentDcHosts != null && currentDcRemaining > 0) {
                    currentDcRemaining--;
                    int c = idx++ % currentDcHosts.size();
                    if (c < 0)
                        c += currentDcHosts.size();
                    return currentDcHosts.get(c);
                }

                if (dontHopForLocalCL && query.getConsistencyLevel().isDCLocal())
                    return endOfData();

                if (remoteDcs == null) {
                    Set<String> copy = new HashSet<String>(perDcLiveHosts.keySet());
                    copy.remove(localDc);
                    remoteDcs = copy.iterator();
                }

                if (!remoteDcs.hasNext())
                    return endOfData();

                String nextRemoteDc = remoteDcs.next();
                CopyOnWriteArrayList<Host> nextDcHosts = perDcLiveHosts.get(nextRemoteDc);
                if (nextDcHosts != null) {
                    // Clone for thread safety
                    List<Host> dcHosts = cloneList(nextDcHosts);
                    currentDcHosts = dcHosts.subList(0, Math.min(dcHosts.size(), usedHostsPerRemoteDc));
                    currentDcRemaining = currentDcHosts.size();
                }

                return computeNext();
            }
        };
    }

    @Override
    public void onUp(Host host) {
        String dc = dc(host);
        CopyOnWriteArrayList<Host> dcHosts = perDcLiveHosts.get(dc);
        if (dcHosts == null) {
            CopyOnWriteArrayList<Host> newMap = new CopyOnWriteArrayList<Host>(Collections.singletonList(host));
            dcHosts = perDcLiveHosts.putIfAbsent(dc, newMap);
            // If we've successfully put our new host, we're good, otherwise we've been beaten so continue
            if (dcHosts == null)
                return;
        }
        dcHosts.addIfAbsent(host);
    }

    @Override
    public void onDown(Host host) {
        CopyOnWriteArrayList<Host> dcHosts = perDcLiveHosts.get(dc(host));
        if (dcHosts != null)
            dcHosts.remove(host);
    }

    @Override
    public void onAdd(Host host) {
        onUp(host);
    }

    @Override
    public void onRemove(Host host) {
        onDown(host);
    }
}
