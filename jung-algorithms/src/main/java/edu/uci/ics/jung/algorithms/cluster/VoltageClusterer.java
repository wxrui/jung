/*
 * Copyright (c) 2004, The JUNG Authors
 *
 * All rights reserved.
 *
 * This software is open-source under the BSD license; see either
 * "license.txt" or
 * https://github.com/jrtom/jung/blob/master/LICENSE for a description.
 *
 * Created on Aug 12, 2004
 */
package edu.uci.ics.jung.algorithms.cluster;

import com.google.common.base.Preconditions;
import com.google.common.graph.Network;
import com.google.common.math.Stats;
import edu.uci.ics.jung.algorithms.scoring.VoltageScorer;
import edu.uci.ics.jung.algorithms.util.KMeansClusterer;
import edu.uci.ics.jung.algorithms.util.KMeansClusterer.NotEnoughClustersException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Clusters nodes of a <code>Network</code> based on their ranks as calculated by <code>
 * VoltageScorer</code>. This algorithm is based on, but not identical with, the method described in
 * the paper below. The primary difference is that Wu and Huberman assume a priori that the clusters
 * are of approximately the same size, and therefore use a more complex method than k-means (which
 * is used here) for determining cluster membership based on co-occurrence data.
 *
 * <p>The algorithm proceeds as follows:
 *
 * <ul>
 *   <li>first, generate a set of candidate clusters as follows:
 *       <ul>
 *         <li>pick (widely separated) node pair, run VoltageScorer
 *         <li>group the nodes in two clusters according to their voltages
 *         <li>store resulting candidate clusters
 *       </ul>
 *   <li>second, generate k-1 clusters as follows:
 *       <ul>
 *         <li>pick a node v as a cluster 'seed' <br>
 *             (Wu/Huberman: most frequent node in candidate clusters)
 *         <li>calculate co-occurrence over all candidate clusters of v with each other node
 *         <li>separate co-occurrence counts into high/low; high nodes constitute a cluster
 *         <li>remove v's nodes from candidate clusters; continue
 *       </ul>
 *   <li>finally, remaining unassigned nodes are assigned to the kth ("garbage") cluster.
 * </ul>
 *
 * <p><b>NOTE</b>: Depending on how the co-occurrence data splits the data into clusters, the number
 * of clusters returned by this algorithm may be less than the number of clusters requested. The
 * number of clusters will never be more than the number requested, however.
 *
 * @author Joshua O'Madadhain
 * @see "'Finding communities in linear time: a physics approach', Fang Wu and Bernardo Huberman,
 *     http://www.hpl.hp.com/research/idl/papers/linear/"
 * @see VoltageScorer
 * @see KMeansClusterer
 */
public class VoltageClusterer<V, E> {
  protected int num_candidates;
  protected KMeansClusterer<V> kmc;
  protected Random rand;
  protected Network<V, E> g;

  /**
   * Creates an instance of a VoltageCluster with the specified parameters. These are mostly
   * parameters that are passed directly to VoltageScorer and KMeansClusterer.
   *
   * @param g the graph whose nodes are to be clustered
   * @param num_candidates the number of candidate clusters to create
   */
  public VoltageClusterer(Network<V, E> g, int num_candidates) {
    Preconditions.checkArgument(num_candidates >= 1, "must generate >= 1 candidates");

    this.num_candidates = num_candidates;
    this.kmc = new KMeansClusterer<V>();
    rand = new Random();
    this.g = g;
  }

  protected void setRandomSeed(int random_seed) {
    rand = new Random(random_seed);
  }

  /**
   * @param v the node whose community we wish to discover
   * @return a community (cluster) centered around <code>v</code>.
   */
  public Collection<Set<V>> getCommunity(V v) {
    return cluster_internal(v, 2);
  }

  /**
   * Clusters the nodes of <code>g</code> into <code>num_clusters</code> clusters, based on their
   * connectivity.
   *
   * @param num_clusters the number of clusters to identify
   * @return a collection of clusters (sets of nodes)
   */
  public Collection<Set<V>> cluster(int num_clusters) {
    return cluster_internal(null, num_clusters);
  }

  /**
   * Does the work of <code>getCommunity</code> and <code>cluster</code>.
   *
   * @param origin the node around which clustering is to be done
   * @param num_clusters the (maximum) number of clusters to find
   * @return a collection of clusters (sets of nodes)
   */
  protected Collection<Set<V>> cluster_internal(V origin, int num_clusters) {
    // generate candidate clusters
    // repeat the following 'samples' times:
    // * pick (widely separated) node pair, run VoltageScorer
    // * use k-means to identify 2 communities in ranked graph
    // * store resulting candidate communities
    ArrayList<V> v_array = new ArrayList<V>(g.nodes());

    LinkedList<Set<V>> candidates = new LinkedList<Set<V>>();

    for (int j = 0; j < num_candidates; j++) {
      V source;
      if (origin == null) {
        source = v_array.get((int) (rand.nextDouble() * v_array.size()));
      } else {
        source = origin;
      }
      V target = null;
      do {
        target = v_array.get((int) (rand.nextDouble() * v_array.size()));
      } while (source == target);
      VoltageScorer<V, E> vs = new VoltageScorer<V, E>(g, source, target);
      vs.evaluate();

      Map<V, double[]> voltage_ranks = new HashMap<V, double[]>();
      for (V v : g.nodes()) {
        voltage_ranks.put(v, new double[] {vs.getNodeScore(v)});
      }

      //            addOneCandidateCluster(candidates, voltage_ranks);
      addTwoCandidateClusters(candidates, voltage_ranks);
    }

    // repeat the following k-1 times:
    // * pick a node v as a cluster seed
    //   (Wu/Huberman: most frequent node in candidates)
    // * calculate co-occurrence (in candidate clusters)
    //   of this node with all others
    // * use k-means to separate co-occurrence counts into high/low;
    //   high nodes are a cluster
    // * remove v's nodes from candidate clusters

    Collection<Set<V>> clusters = new LinkedList<Set<V>>();
    Set<V> remaining = new HashSet<V>(g.nodes());

    List<V> seed_candidates = getSeedCandidates(candidates);
    int seed_index = 0;

    for (int j = 0; j < (num_clusters - 1); j++) {
      if (remaining.isEmpty()) {
        break;
      }

      V seed;
      if (seed_index == 0 && origin != null) {
        seed = origin;
      } else {
        do {
          seed = seed_candidates.get(seed_index++);
        } while (!remaining.contains(seed));
      }

      Map<V, double[]> occur_counts = getObjectCounts(candidates, seed);
      if (occur_counts.size() < 2) {
        break;
      }

      // now that we have the counts, cluster them...
      try {
        Collection<Map<V, double[]>> high_low = kmc.cluster(occur_counts, 2);
        // ...get the cluster with the highest-valued centroid...
        Iterator<Map<V, double[]>> h_iter = high_low.iterator();
        Map<V, double[]> cluster1 = h_iter.next();
        Map<V, double[]> cluster2 = h_iter.next();
        double[] centroid1 = meansOf(cluster1.values());
        double[] centroid2 = meansOf(cluster2.values());
        Set<V> new_cluster;
        if (centroid1[0] >= centroid2[0]) {
          new_cluster = cluster1.keySet();
        } else {
          new_cluster = cluster2.keySet();
        }

        // ...remove the elements of new_cluster from each candidate...
        for (Set<V> cluster : candidates) {
          cluster.removeAll(new_cluster);
        }
        clusters.add(new_cluster);
        remaining.removeAll(new_cluster);
      } catch (NotEnoughClustersException nece) {
        // all remaining nodes are in the same cluster
        break;
      }
    }

    // identify remaining nodes (if any) as a 'garbage' cluster
    if (!remaining.isEmpty()) {
      clusters.add(remaining);
    }

    return clusters;
  }

  private static double[] meansOf(Collection<double[]> collectionOfDoubleArrays) {
    double[] result = new double[collectionOfDoubleArrays.size()];
    int index = 0;
    for (double[] array : collectionOfDoubleArrays) {
      result[index++] = Stats.meanOf(array);
    }
    return result;
  }

  /**
   * Do k-means with three intervals and pick the smaller two clusters (presumed to be on the ends);
   * this is closer to the Wu-Huberman method.
   *
   * @param candidates the list of clusters to populate
   * @param voltage_ranks the voltage values for each node
   */
  protected void addTwoCandidateClusters(
      LinkedList<Set<V>> candidates, Map<V, double[]> voltage_ranks) {
    try {
      List<Map<V, double[]>> clusters =
          new ArrayList<Map<V, double[]>>(kmc.cluster(voltage_ranks, 3));
      boolean b01 = clusters.get(0).size() > clusters.get(1).size();
      boolean b02 = clusters.get(0).size() > clusters.get(2).size();
      boolean b12 = clusters.get(1).size() > clusters.get(2).size();
      if (b01 && b02) {
        candidates.add(clusters.get(1).keySet());
        candidates.add(clusters.get(2).keySet());
      } else if (!b01 && b12) {
        candidates.add(clusters.get(0).keySet());
        candidates.add(clusters.get(2).keySet());
      } else if (!b02 && !b12) {
        candidates.add(clusters.get(0).keySet());
        candidates.add(clusters.get(1).keySet());
      }
    } catch (NotEnoughClustersException e) {
      // no valid candidates, continue
    }
  }

  /**
   * alternative to addTwoCandidateClusters(): cluster nodes by voltages into 2 clusters. We only
   * consider the smaller of the two clusters returned by k-means to be a 'true' cluster candidate;
   * the other is a garbage cluster.
   *
   * @param candidates the list of clusters to populate
   * @param voltage_ranks the voltage values for each node
   */
  protected void addOneCandidateCluster(
      LinkedList<Set<V>> candidates, Map<V, double[]> voltage_ranks) {
    try {
      List<Map<V, double[]>> clusters;
      clusters = new ArrayList<Map<V, double[]>>(kmc.cluster(voltage_ranks, 2));
      if (clusters.get(0).size() < clusters.get(1).size()) {
        candidates.add(clusters.get(0).keySet());
      } else {
        candidates.add(clusters.get(1).keySet());
      }
    } catch (NotEnoughClustersException e) {
      // no valid candidates, continue
    }
  }

  /**
   * Returns a list of cluster seeds, ranked in decreasing order of number of appearances in the
   * specified collection of candidate clusters.
   *
   * @param candidates the set of candidate clusters
   * @return a set of cluster seeds
   */
  protected List<V> getSeedCandidates(Collection<Set<V>> candidates) {
    final Map<V, double[]> occur_counts = getObjectCounts(candidates, null);

    ArrayList<V> occurrences = new ArrayList<V>(occur_counts.keySet());
    Collections.sort(occurrences, new MapValueArrayComparator(occur_counts));

    //        System.out.println("occurrences: ");
    for (int i = 0; i < occurrences.size(); i++) {
      System.out.println(occur_counts.get(occurrences.get(i))[0]);
    }

    return occurrences;
  }

  protected Map<V, double[]> getObjectCounts(Collection<Set<V>> candidates, V seed) {
    Map<V, double[]> occur_counts = new HashMap<V, double[]>();
    for (V v : g.nodes()) {
      occur_counts.put(v, new double[] {0});
    }

    for (Set<V> candidate : candidates) {
      if (seed == null) {
        System.out.println(candidate.size());
      }
      if (seed == null || candidate.contains(seed)) {
        for (V element : candidate) {
          double[] count = occur_counts.get(element);
          count[0]++;
        }
      }
    }

    if (seed == null) {
      System.out.println("occur_counts size: " + occur_counts.size());
      for (V v : occur_counts.keySet()) {
        System.out.println(occur_counts.get(v)[0]);
      }
    }

    return occur_counts;
  }

  protected class MapValueArrayComparator implements Comparator<V> {
    private Map<V, double[]> map;

    protected MapValueArrayComparator(Map<V, double[]> map) {
      this.map = map;
    }

    public int compare(V o1, V o2) {
      double[] count0 = map.get(o1);
      double[] count1 = map.get(o2);
      if (count0[0] < count1[0]) {
        return 1;
      } else if (count0[0] > count1[0]) {
        return -1;
      }
      return 0;
    }
  }
}
