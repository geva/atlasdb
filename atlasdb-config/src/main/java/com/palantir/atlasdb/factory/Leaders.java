/**
 * Copyright 2015 Palantir Technologies
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.factory;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLSocketFactory;

import org.immutables.value.Value;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.palantir.atlasdb.config.LeaderConfig;
import com.palantir.atlasdb.factory.TransactionManagers.Environment;
import com.palantir.atlasdb.http.AtlasDbHttpClients;
import com.palantir.atlasdb.http.NotCurrentLeaderExceptionMapper;
import com.palantir.leader.LeaderElectionService;
import com.palantir.leader.PaxosLeaderElectionService;
import com.palantir.leader.PingableLeader;
import com.palantir.paxos.PaxosAcceptor;
import com.palantir.paxos.PaxosAcceptorImpl;
import com.palantir.paxos.PaxosLearner;
import com.palantir.paxos.PaxosLearnerImpl;
import com.palantir.paxos.PaxosProposer;
import com.palantir.paxos.PaxosProposerImpl;

public final class Leaders {
    private Leaders() {
        // Utility class
    }

    /**
     * Creates a LeaderElectionService using the supplied configuration and
     * registers appropriate endpoints for that service.
     */
    public static LeaderElectionService create(Environment env, LeaderConfig config) {
        LocalPaxosServices localPaxosServices = createLocalServices(config);

        env.register(localPaxosServices.ourAcceptor());
        env.register(localPaxosServices.ourLearner());
        env.register(localPaxosServices.leaderElectionService());
        env.register(new NotCurrentLeaderExceptionMapper());

        return localPaxosServices.leaderElectionService();
    }

    public static LocalPaxosServices createLocalServices(LeaderConfig config) {
        Set<String> remoteLeaderUris = Sets.newHashSet(config.leaders());
        remoteLeaderUris.remove(config.localServer());

        RemotePaxosServerSpec remotePaxosServerSpec = ImmutableRemotePaxosServerSpec.builder()
                .remoteLeaderUris(remoteLeaderUris)
                .remoteAcceptorUris(remoteLeaderUris)
                .remoteLearnerUris(remoteLeaderUris)
                .build();
        return createLocalServices(config, remotePaxosServerSpec);
    }

    public static LocalPaxosServices createLocalServices(LeaderConfig config,
            RemotePaxosServerSpec remotePaxosServerSpec) {
        PaxosAcceptor ourAcceptor = PaxosAcceptorImpl.newAcceptor(config.acceptorLogDir().getPath());
        PaxosLearner ourLearner = PaxosLearnerImpl.newLearner(config.learnerLogDir().getPath());

        Optional<SSLSocketFactory> sslSocketFactory =
                TransactionManagers.createSslSocketFactory(config.sslConfiguration());

        List<PaxosLearner> learners = createProxyAndLocalList(
                ourLearner, remotePaxosServerSpec.remoteLearnerUris(), sslSocketFactory, PaxosLearner.class);
        List<PaxosAcceptor> acceptors = createProxyAndLocalList(
                ourAcceptor, remotePaxosServerSpec.remoteAcceptorUris(), sslSocketFactory, PaxosAcceptor.class);

        Map<PingableLeader, HostAndPort> otherLeaders = generatePingables(
                remotePaxosServerSpec.remoteLeaderUris(), sslSocketFactory);

        ExecutorService executor = Executors.newCachedThreadPool(new ThreadFactoryBuilder()
                .setNameFormat("atlas-leaders-%d")
                .setDaemon(true)
                .build());

        PaxosProposer proposer = createPaxosProposer(ourLearner, acceptors, learners, config.quorumSize(), executor);

        PaxosLeaderElectionService leader = new PaxosLeaderElectionService(
                proposer,
                ourLearner,
                otherLeaders,
                ImmutableList.copyOf(acceptors),
                ImmutableList.copyOf(learners),
                executor,
                config.pingRateMs(),
                config.randomWaitBeforeProposingLeadershipMs(),
                config.leaderPingResponseWaitMs());

        return ImmutableLocalPaxosServices.builder()
                .ourAcceptor(ourAcceptor)
                .ourLearner(ourLearner)
                .leaderElectionService(leader)
                .pingableLeader(leader)
                .build();
    }

    public static PaxosProposer createPaxosProposer(
            PaxosLearner ourLearner,
            List<PaxosAcceptor> acceptors,
            List<PaxosLearner> learners,
            int quorumSize,
            ExecutorService executor) {
        return PaxosProposerImpl.newProposer(
                    ourLearner,
                    ImmutableList.copyOf(acceptors),
                    ImmutableList.copyOf(learners),
                    quorumSize,
                    executor);
    }

    public static <T> List<T> createProxyAndLocalList(
            T localObject,
            Set<String> remoteUris,
            Optional<SSLSocketFactory> sslSocketFactory,
            Class<T> clazz) {
        List<T> objects = AtlasDbHttpClients.createProxies(sslSocketFactory, remoteUris, clazz);
        objects.add(localObject);
        return objects;
    }

    public static Map<PingableLeader, HostAndPort> generatePingables(
            Collection<String> remoteEndpoints,
            Optional<SSLSocketFactory> sslSocketFactory) {
        /* The interface used as a key here may be a proxy, which may have strange .equals() behavior.
         * This is circumvented by using an IdentityHashMap which will just use native == for equality.
         */
        Map<PingableLeader, HostAndPort> pingables = new IdentityHashMap<>();
        for (String endpoint : remoteEndpoints) {
            PingableLeader remoteInterface = AtlasDbHttpClients
                    .createProxy(sslSocketFactory, endpoint, PingableLeader.class);
            HostAndPort hostAndPort = HostAndPort.fromString(endpoint);
            pingables.put(remoteInterface, hostAndPort);
        }
        return pingables;
    }

    @Value.Immutable
    public interface LocalPaxosServices {
        PaxosAcceptor ourAcceptor();
        PaxosLearner ourLearner();
        LeaderElectionService leaderElectionService();
        PingableLeader pingableLeader();
    }

    @Value.Immutable
    public interface RemotePaxosServerSpec {
        Set<String> remoteLeaderUris();
        Set<String> remoteAcceptorUris();
        Set<String> remoteLearnerUris();
    }
}
