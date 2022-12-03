/*
 * Copyright (C) 2015 Brian Dupras
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package top.iseason.bukkit.sakurabind.cuckoofilter;

import com.google.common.annotations.Beta;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.hash.Funnel;
import com.google.common.primitives.SignedBytes;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import java.io.*;
import java.util.Collection;

import static com.google.common.math.DoubleMath.log2;
import static com.google.common.math.LongMath.divide;
import static java.lang.Math.ceil;
import static java.lang.Math.pow;
import static java.math.RoundingMode.CEILING;
import static java.math.RoundingMode.HALF_DOWN;
import static top.iseason.bukkit.sakurabind.cuckoofilter.Preconditions.checkArgument;
import static top.iseason.bukkit.sakurabind.cuckoofilter.Preconditions.checkNotNull;

/**
 * A Cuckoo filter for instances of {@code E} that implements the {@link ProbabilisticFilter}
 * interface.
 *
 * <blockquote>"Cuckoo filters can replace Bloom filters for approximate set membership tests.
 * Cuckoo filters support adding and removing items dynamically while achieving even higher
 * performance than Bloom filters. For applications that store many items and target moderately low
 * false positive rates, cuckoo filters have lower space overhead than space-optimized Bloom
 * filters. Cuckoo filters outperform previous data structures that extend Bloom filters to support
 * deletions substantially in both time and space." - Fan, et. al.</blockquote>
 *
 * <p>Cuckoo filters offer constant time performance for the basic operations {@link #add(Object)},
 * {@link #remove(Object)}, {@link #contains(Object)} and {@link #sizeLong()}.</p>
 *
 * <p>This class does not permit {@code null} elements.</p>
 *
 * <p>Cuckoo filters implement the {@link Serializable} interface. They also support a more compact
 * serial representation via the {@link #writeTo(OutputStream)} and {@link #readFrom(InputStream,
 * Funnel)} methods. Both serialized forms will continue to be supported by future versions of this
 * library. However, serial forms generated by newer versions of the code may not be readable by
 * older versions of the code (e.g., a serialized cuckoo filter generated today may <i>not</i> be
 * readable by a binary that was compiled 6 months ago).</p>
 *
 * <p><i>ref: <a href="https://www.cs.cmu.edu/~dga/papers/cuckoo-conext2014.pdf">Cuckoo Filter:
 * Practically Better Than Bloom</a></i> Bin Fan, David G. Andersen, Michael Kaminsky†, Michael D.
 * Mitzenmacher‡ Carnegie Mellon University, †Intel Labs, ‡Harvard University</p>
 *
 * @param <E> the type of elements that this filter accepts
 * @author Brian Dupras
 * @author Alex Beal
 * @see ProbabilisticFilter
 */
@Beta
public final class CuckooFilter<E> implements ProbabilisticFilter<E>, Serializable {
  static final int MAX_ENTRIES_PER_BUCKET = 8;
  static final int MIN_ENTRIES_PER_BUCKET = 2;

  /**
   * Minimum false positive probability supported, 8.67E-19.
   * <p>
   * CuckooFilter § 5.1 Eq. (6), "f ≥ log2(2b/e) = [log2(1/e) + log2(2b)]"
   * (b) entries per bucket: 8 at e <= 0.00001
   * (f) bits per entry: 64-bits max
   * (e) false positive probability
   * <p>
   * 64 = log2(16/e) = [log2(1/e) + log2(16)]
   * 64 = log2(1/e) + 4
   * 60 = log2(1/e)
   * 2^60 = 1/e
   * e = 1/2^60
   * e = 8.673617379884035E-19
   */
  static double MIN_FPP = 1.0D / pow(2, 60);

  /**
   * Maximum false positive probability supported, 0.99.
   */
  static double MAX_FPP = 0.99D;

  private final CuckooTable table;
  private final Funnel<? super E> funnel;
  private final CuckooStrategy cuckooStrategy;
  private final double fpp;

  /**
   * Creates a CuckooFilter.
   */
  private CuckooFilter(
          CuckooTable table, Funnel<? super E> funnel, CuckooStrategy cuckooStrategy, double fpp) {
    this.fpp = fpp;
    this.table = checkNotNull(table);
    this.funnel = checkNotNull(funnel);
    this.cuckooStrategy = checkNotNull(cuckooStrategy);
  }

  /**
   * Creates a filter with the expected number of insertions and expected false positive
   * probability. <p/> <p>Note that overflowing a {@link CuckooFilter} with significantly more
   * objects than specified, will result in its saturation causing {@link #add(Object)} to reject
   * new additions. <p/> <p>The constructed {@link CuckooFilter} will be serializable if the
   * provided {@code Funnel<T>} is. <p/> <p>It is recommended that the funnel be implemented as a
   * Java enum. This has the benefit of ensuring proper serialization and deserialization, which is
   * important since {@link #equals} also relies on object identity of funnels.
   *
   * @param funnel   the funnel of T's that the constructed {@link CuckooFilter} will use
   * @param capacity the number of expected insertions to the constructed {@link CuckooFilter}; must
   *                 be positive
   * @param fpp      the desired false positive probability (must be positive and less than 1.0).
   * @return a {@link CuckooFilter}
   */
  @CheckReturnValue
  public static <T> CuckooFilter<T> create(
          Funnel<? super T> funnel, long capacity, double fpp) {
    return create(funnel, capacity, fpp,
            CuckooStrategies.MURMUR128_BEALDUPRAS_32.strategy());
  }

  @VisibleForTesting
  static <T> CuckooFilter<T> create(Funnel<? super T> funnel, long capacity, double fpp,
                                    CuckooStrategy cuckooStrategy) {
    checkNotNull(funnel);
    checkArgument(capacity > 0, "Expected insertions (%s) must be > 0", capacity);
    checkArgument(fpp > 0.0D, "False positive probability (%s) must be > 0.0", fpp);
    checkArgument(fpp < 1.0D, "False positive probability (%s) must be < 1.0", fpp);
    checkNotNull(cuckooStrategy);

    int numEntriesPerBucket = optimalEntriesPerBucket(fpp);
    long numBuckets = optimalNumberOfBuckets(capacity, numEntriesPerBucket);
    int numBitsPerEntry = optimalBitsPerEntry(fpp, numEntriesPerBucket);

    try {
      return new CuckooFilter<T>(new CuckooTable(numBuckets,
              numEntriesPerBucket, numBitsPerEntry), funnel, cuckooStrategy, fpp);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Could not create CuckooFilter of " + numBuckets +
              " buckets, " + numEntriesPerBucket + " entries per bucket, " + numBitsPerEntry +
              " bits per entry", e);
    }
  }

  /**
   * Creates a filter with the expected number of insertions and a default expected false positive
   * probability of 3.2%. <p/> <p>Note that overflowing a {@code CuckooFilter} with significantly
   * more objects than specified, will result in its saturation causing {@link #add(Object)} to
   * reject new additions. <p/> <p>The constructed {@link CuckooFilter} will be serializable if the
   * provided {@code Funnel<T>} is. <p/> <p>It is recommended that the funnel be implemented as a
   * Java enum. This has the benefit of ensuring proper serialization and deserialization, which is
   * important since {@link #equals} also relies on object identity of funnels.
   *
   * @param funnel   the funnel of T's that the constructed {@link CuckooFilter} will use
   * @param capacity the number of expected insertions to the constructed {@link CuckooFilter}; must
   *                 be positive
   * @return a {@link CuckooFilter}
   */
  @CheckReturnValue
  public static <T> CuckooFilter<T> create(Funnel<? super T> funnel, long capacity) {
    return create(funnel, capacity, 0.032D);
  }

  /**
   * Returns the optimal number of entries per bucket, or bucket size, ({@code b}) given the
   * expected false positive probability ({@code e}).
   * <p>
   * CuckooFilter § 5.1 ¶ 5, "Optimal bucket size"
   *
   * @param e the desired false positive probability (must be positive and less than 1.0)
   * @return optimal number of entries per bucket
   */
  @VisibleForTesting
  static int optimalEntriesPerBucket(double e) {
    checkArgument(e > 0.0D, "e must be > 0.0");
    if (e <= 0.00001) {
      return MAX_ENTRIES_PER_BUCKET;
    } else if (e <= 0.002) {
      return MAX_ENTRIES_PER_BUCKET / 2;
    } else {
      return MIN_ENTRIES_PER_BUCKET;
    }
  }

  /**
   * Returns the optimal load factor ({@code a}) given the number of entries per bucket ({@code
   * b}).
   * <p>
   * CuckooFilter § 5.1 ¶ 2, "(1) Larger buckets improve table occupancy"
   *
   * @param b number of entries per bucket
   * @return load factor, positive and less than 1.0
   */
  @VisibleForTesting
  static double optimalLoadFactor(int b) {
    checkArgument(b == 2 || b == 4 || b == 8, "b must be 2, 4, or 8");
    if (b == 2) {
      return 0.84D;
    } else if (b == 4) {
      return 0.955D;
    } else {
      return 0.98D;
    }
  }

  /**
   * Returns the optimal number of bits per entry ({@code f}) given the false positive probability
   * ({@code e}) and the number of entries per bucket ({@code b}).
   * <p>
   * CuckooFilter § 5.1 Eq. (6), "f ≥ log2(2b/e) = [log2(1/e) + log2(2b)]"
   *
   * @param e the desired false positive probability (must be positive and less than 1.0)
   * @param b number of entries per bucket
   * @return number of bits per entry
   */
  @VisibleForTesting
  static int optimalBitsPerEntry(double e, int b) {
    checkArgument(e >= MIN_FPP, "Cannot create CuckooFilter with FPP[" + e +
            "] < CuckooFilter.MIN_FPP[" + CuckooFilter.MIN_FPP + "]");
    return log2(2 * b / e, HALF_DOWN);
  }

  /**
   * Returns the minimal required number of buckets given the expected insertions {@code n}, and the
   * number of entries per bucket ({@code b}).
   *
   * @param n the number of expected insertions
   * @param b number of entries per bucket
   * @return number of buckets
   */
  @VisibleForTesting
  static long optimalNumberOfBuckets(long n, int b) {
    checkArgument(n > 0, "n must be > 0");
    return evenCeil(divide((long) ceil(n / optimalLoadFactor(b)), b, CEILING));
  }

  static long evenCeil(long n) {
    return (n + 1) / 2 * 2;
  }

  /**
   * Reads a byte stream, which was written by {@link #writeTo(OutputStream)}, into a {@link
   * CuckooFilter}. <p/> The {@code Funnel} to be used is not encoded in the stream, so it must be
   * provided here. <b>Warning:</b> the funnel provided <b>must</b> behave identically to the one
   * used to populate the original Cuckoo filter!
   *
   * @throws IOException if the InputStream throws an {@code IOException}, or if its data does not
   *                     appear to be a CuckooFilter serialized using the {@link
   *                     #writeTo(OutputStream)} method.
   */
  @CheckReturnValue
  public static <T> CuckooFilter<T> readFrom(InputStream in, Funnel<T> funnel) throws IOException {
    checkNotNull(in, "InputStream");
    checkNotNull(funnel, "Funnel");
    int strategyOrdinal = -1;
    double fpp = -1.0D;
    long size = -1L;
    long checksum = -1L;
    long numBuckets = -1L;
    int numEntriesPerBucket = -1;
    int numBitsPerEntry = -1;
    int dataLength = -1;
    try {
      DataInputStream din = new DataInputStream(in);
      // currently this assumes there is no negative ordinal; will have to be updated if we
      // add non-stateless strategies (for which we've reserved negative ordinals; see
      // Strategy.ordinal()).
      strategyOrdinal = din.readByte();
      fpp = din.readDouble();
      size = din.readLong();
      checksum = din.readLong();
      numBuckets = din.readLong();
      numEntriesPerBucket = din.readInt();
      numBitsPerEntry = din.readInt();
      dataLength = din.readInt();

      CuckooStrategy cuckooStrategy = CuckooStrategies.values()[strategyOrdinal].strategy();
      long[] data = new long[dataLength];
      for (int i = 0; i < data.length; i++) {
        data[i] = din.readLong();
      }
      return new CuckooFilter<T>(
              new CuckooTable(data, size, checksum, numBuckets, numEntriesPerBucket, numBitsPerEntry),
              funnel, cuckooStrategy, fpp);
    } catch (RuntimeException e) {
      IOException ioException = new IOException(
              "Unable to deserialize CuckooFilter from InputStream."
                      + " strategyOrdinal: " + strategyOrdinal
                      + " fpp: " + fpp
                      + " size: " + size
                      + " checksum: " + checksum
                      + " numBuckets: " + numBuckets
                      + " numEntriesPerBucket: " + numEntriesPerBucket
                      + " numBitsPerEntry: " + numBitsPerEntry
                      + " dataLength: " + dataLength, e);
      throw ioException;
    }
  }

  /**
   * Returns the number of longs required by a CuckooTable for storage given the dimensions chosen
   * by the CuckooFilter to support {@code capacity) @ {@code fpp}.
   * <p>
   * CuckooTable current impl uses a single long[] for data storage, so the calculated value must be
   * <= Integer.MAX_VALUE at this time.
   */
  @VisibleForTesting
  static int calculateDataLength(long capacity, double fpp) {
    return CuckooTable.calculateDataLength(
            optimalNumberOfBuckets(capacity, optimalEntriesPerBucket(fpp)),
            optimalEntriesPerBucket(fpp),
            optimalBitsPerEntry(fpp, optimalEntriesPerBucket(fpp)));
  }

  /**
   * Returns a new {@link CuckooFilter} that's a copy of this instance. The new instance is equal to
   * this instance but shares no mutable state.
   */
  @CheckReturnValue
  public CuckooFilter<E> copy() {
    return new CuckooFilter<E>(table.copy(), funnel, cuckooStrategy, fpp);
  }

  /**
   * Returns {@code true} if this filter <i>might</i> contain the specified element, {@code false}
   * if this is <i>definitely</i> not the case.
   *
   * @param e element whose containment in this filter is to be tested
   * @return {@code true} if this filter <i>might</i> contain the specified element, {@code false}
   * if this is <i>definitely</i> not the case.
   * @throws NullPointerException if the specified element is {@code null} and this filter does not
   *                              permit {@code null} elements
   * @see #containsAll(Collection)
   * @see #containsAll(ProbabilisticFilter)
   * @see #add(Object)
   * @see #remove(Object)
   */
  @CheckReturnValue
  public boolean contains(E e) {
    checkNotNull(e);
    return cuckooStrategy.contains(e, funnel, table);
  }

  /**
   * Returns {@code true} if this filter <i>might</i> contain all of the elements of the specified
   * collection. More formally, returns {@code true} if {@link #contains(Object)} {@code == true}
   * for all of the elements of the specified collection.
   *
   * @param c collection containing elements to be checked for containment in this filter
   * @return {@code true} if this filter <i>might</i> contain all elements of the specified
   * collection
   * @throws NullPointerException if the specified collection contains one or more {@code null}
   *                              elements, or if the specified collection is {@code null}
   * @see #contains(Object)
   * @see #containsAll(ProbabilisticFilter)
   */
  public boolean containsAll(Collection<? extends E> c) {
    checkNotNull(c);
    for (E e : c) {
      checkNotNull(e);
      if (!contains(e)) return false;
    }
    return true;
  }

  /**
   * Returns {@code true} if this filter <i>might</i> contain all elements contained in the
   * specified filter. {@link #isCompatible(ProbabilisticFilter)} must return {@code true} for the
   * given filter.
   *
   * @param f cuckoo filter containing elements to be checked for probable containment in this
   *          filter
   * @return {@code true} if this filter <i>might</i> contain all elements contained in the
   * specified filter, {@code false} if this is <i>definitely</i> not the case.
   * @throws NullPointerException     if the specified filter is {@code null}
   * @throws IllegalArgumentException if {@link #isCompatible(ProbabilisticFilter)} {@code == false}
   *                                  given {@code f}
   * @see #contains(Object)
   * @see #containsAll(Collection)
   */
  public boolean containsAll(ProbabilisticFilter<E> f) {
    checkNotNull(f);
    if (this == f) {
      return true;
    }
    checkCompatibility(f, "compare");
    return this.cuckooStrategy.containsAll(this.table, ((CuckooFilter) f).table);
  }

  /**
   * Adds the specified element to this filter. Returns {@code true} if {@code e} was successfully
   * added to the filter, {@code false} if this is <i>definitely</i> not the case, as would be the
   * case when the filter becomes saturated. Saturation may occur even if {@link #sizeLong()} {@code
   * < } {@link #capacity()}, e.g. if {@code e} has already been added {@code 2*b} times to the
   * cuckoo filter, it will have saturated the number of entries per bucket ({@code b}) allocated
   * within the filter and a subsequent invocation will return {@code false}. A return value of
   * {@code true} ensures that {@link #contains(Object)} given {@code e} will also return {@code
   * true}.
   *
   * @param e element to be added to this filter
   * @return {@code true} if {@code e} was successfully added to the filter, {@code false} if this
   * is <i>definitely</i> not the case
   * @throws NullPointerException if the specified element is {@code null}
   * @see #contains(Object)
   * @see #addAll(Collection)
   * @see #addAll(ProbabilisticFilter)
   */
  @CheckReturnValue
  public boolean add(E e) {
    checkNotNull(e);
    return cuckooStrategy.add(e, funnel, table);
  }

  /**
   * Combines {@code this} filter with another compatible filter. The mutations happen to {@code
   * this} instance. Callers must ensure {@code this} filter is appropriately sized to avoid
   * saturating it or running out of space.
   *
   * @param f filter to be combined into {@code this} filter - {@code f} is not mutated
   * @return {@code true} if the operation was successful, {@code false} otherwise
   * @throws NullPointerException     if the specified filter is {@code null}
   * @throws IllegalArgumentException if {@link #isCompatible(ProbabilisticFilter)} {@code ==
   *                                  false}
   * @see #add(Object)
   * @see #addAll(Collection)
   * @see #contains(Object)
   */
  @CheckReturnValue
  public boolean addAll(ProbabilisticFilter<E> f) {
    checkNotNull(f);
    checkArgument(this != f, "Cannot combine a " + this.getClass().getSimpleName() +
            " with itself.");
    checkCompatibility(f, "combine");
    return this.cuckooStrategy.addAll(this.table, ((CuckooFilter) f).table);
  }

  /**
   * Adds all of the elements in the specified collection to this filter. The behavior of this
   * operation is undefined if the specified collection is modified while the operation is in
   * progress. Some elements of {@code c} may have been added to the filter even when {@code false}
   * is returned. In this case, the caller may {@link #remove(Object)} the additions by comparing
   * the filter {@link #sizeLong()} before and after the invocation, knowing that additions from
   * {@code c} occurred in {@code c}'s iteration order.
   *
   * @param c collection containing elements to be added to this filter
   * @return {@code true} if all elements of the collection were successfully added, {@code false}
   * otherwise
   * @throws NullPointerException if the specified collection contains a {@code null} element, or if
   *                              the specified collection is {@code null}
   * @see #add(Object)
   * @see #addAll(ProbabilisticFilter)
   * @see #contains(Object)
   */
  public boolean addAll(Collection<? extends E> c) {
    checkNotNull(c);
    for (E e : c) {
      checkNotNull(e);
      if (!add(e)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Removes all of the elements from this filter. The filter will be empty after this call
   * returns.
   *
   * @see #sizeLong()
   * @see #isEmpty()
   */
  public void clear() {
    table.clear();
  }

  /**
   * Removes the specified element from this filter. The element must be contained in the filter
   * prior to invocation. If {@code false} is returned, this is <i>definitely</i> an indication that
   * the specified element wasn't contained in the filter prior to invocation. This condition is an
   * error, and this filter can no longer be relied upon to return correct {@code false} responses
   * from {@link #contains(Object)}, unless {@link #isEmpty()} is also {@code true}.
   *
   * @param e element to be removed from this filter
   * @return {@code true} if this filter probably contained the specified element, {@code false}
   * otherwise
   * @throws NullPointerException if the specified element is {@code null} and this filter does not
   *                              permit {@code null} elements
   * @see #contains(Object)
   * @see #removeAll(Collection)
   * @see #removeAll(ProbabilisticFilter)
   */
  @CheckReturnValue
  public boolean remove(E e) {
    checkNotNull(e);
    return cuckooStrategy.remove(e, funnel, table);
  }

  /**
   * Removes from this filter all of its elements that are contained in the specified collection.
   * All element contained in the specified collection must be contained in the filter prior to
   * invocation.
   * <p>
   * If {@code false} is returned, this is <i>definitely</i> an indication that the specified
   * collection contained elements that were not contained in this filter prior to invocation, and
   * this filter can no longer be relied upon to return correct {@code false} responses from {@link
   * #contains(Object)}, unless {@link #isEmpty()} is also {@code true}.
   * <p>
   * Some elements of {@code c} may have been removed from the filter even when {@code false} is
   * returned. In this case, the caller may {@link #add(Object)} the additions by comparing the
   * filter {@link #sizeLong()} before and after the invocation, knowing that removals from {@code
   * c} occurred in {@code c}'s iteration order.
   *
   * @param c collection containing elements to be removed from this filter
   * @return {@code true} if all of the elements of the specified collection were successfully
   * removed from the filter, {@code false} if any of the elements was not successfully removed
   * @throws NullPointerException if the specified collection contains one or more {@code null}
   *                              elements, or if the specified collection is {@code null}
   * @see #contains(Object)
   * @see #remove(Object)
   * @see #removeAll(ProbabilisticFilter)
   */
  @CheckReturnValue
  public boolean removeAll(Collection<? extends E> c) {
    checkNotNull(c);
    for (E e : c) {
      checkNotNull(e);
      if (!remove(e)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Subtracts the specified filter from {@code this} filter. The mutations happen to {@code this}
   * instance. Callers must ensure that the specified filter represents elements that are currently
   * contained in {@code this} filter.
   * <p>
   * If {@code false} is returned, this is <i>definitely</i> an indication that the specified filter
   * contained elements that were not contained in this filter prior to invocation and this filter
   * can no longer be relied upon to return correct {@code false} responses from {@link
   * #contains(Object)}, unless {@link #isEmpty()} is also {@code true}.
   *
   * @param f filter containing elements to remove from {@code this} filter - {@code f} is not
   *          mutated
   * @return {@code true} if the operation was successful, {@code false} otherwise
   * @throws NullPointerException     if the specified filter is null
   * @throws IllegalArgumentException if {@link #isCompatible(ProbabilisticFilter)} {@code == false}
   *                                  given {@code f}
   * @see #contains(Object)
   * @see #remove(Object)
   * @see #removeAll(Collection)
   */
  @CheckReturnValue
  public boolean removeAll(ProbabilisticFilter<E> f) {
    checkNotNull(f);
    if (this == f) {
      clear();
      return true;
    }
    checkCompatibility(f, "remove");
    return this.cuckooStrategy.removeAll(this.table, ((CuckooFilter) f).table);
  }

  /**
   * Returns the number of elements contained in this filter (its cardinality). If this filter
   * contains more than {@code Long.MAX_VALUE} elements, returns {@code Long.MAX_VALUE}.
   *
   * @return the number of elements contained in this filter (its cardinality)
   * @see #capacity()
   * @see #isEmpty()
   * @see #size()
   */
  public long sizeLong() {
    return table.size();
  }

  /**
   * Returns the number of elements contained in this filter (its cardinality). If this filter
   * contains more than {@code Integer.MAX_VALUE} elements, returns {@code Integer.MAX_VALUE}.
   *
   * @return the number of elements contained in this filter (its cardinality)
   * @see #capacity()
   * @see #isEmpty()
   * @see #sizeLong()
   */
  public long size() {
    final long ret = sizeLong();
    return ret > Integer.MAX_VALUE ? Integer.MAX_VALUE : ret;
  }

  /*
   * Space optimization cheat sheet, per CuckooFilter § 5.1 :
   *
   * Given:
   *   n: expected insertions
   *   e: expected false positive probability (e.g. 0.03D for 3% fpp)
   *
   * Choose:
   *   b: bucket size in entries (2, 4, 8)
   *   a: load factor (proportional to b)
   *
   * Calculate:
   *   f: fingerprint size in bits
   *   m: table size in buckets
   *
   *
   * 1) Choose b =     8   | 4 |   2
   *      when e : 0.00001 < e ≤ 0.002
   *      ref: CuckooFilter § 5.1 ¶ 5, "Optimal bucket size"
   *
   * 2) Choose a =  50% | 84% | 95.5% | 98%
   *      when b =   1  |  2  |  4    |  8
   *      ref: CuckooFilter § 5.1 ¶ 2, "(1) Larger buckets improve table occupancy"
   *
   * 2) Optimal f = ceil( log2(2b/e) )
   *    ref: CuckooFilter § 5.1 Eq. (6), "f ≥ log2(2b/e) = [log2(1/e) + log2(2b)]"
   *
   * 3) Required m = evenCeil( ceiling( ceiling( n/a ) / b ) )
   *       Minimum entries (B) = n/a rounded up
   *       Minimum buckets (m) = B/b rounded up to an even number
   */

  /**
   * Returns the number of elements this filter can represent at its requested {@code FPP}. It's
   * sometimes possible to add more elements to a cuckoo filter than its capacity since the load
   * factor used to calculate its optimal storage size is less than 100%.
   *
   * @return the number of elements this filter can represent at its requested {@code FPP}.
   * @see #fpp()
   * @see #currentFpp()
   * @see #sizeLong()
   * @see #optimalLoadFactor(int)
   */
  public long capacity() {
    return (long) Math.floor(table.capacity() * optimalLoadFactor(table.numEntriesPerBucket()));
  }

  /**
   * Returns the approximate {@code FPP} limit of this filter. This is not a hard limit, however a
   * cuckoo filter will not exceed its {@code FPP} by a significant amount as the filter becomes
   * saturated.
   *
   * @return the intended {@code FPP} limit of this filter.
   * @see #currentFpp()
   */
  public double fpp() {
    return table.fppAtGivenLoad(optimalLoadFactor(table.numEntriesPerBucket()));
  }

  /**
   * Returns the current false positive probability ({@code FPP}) of this filter.
   *
   * @return the probability that {@link #contains(Object)} will erroneously return {@code true}
   * given an element that has not actually been added to the filter. Unlike a bloom filter, a
   * cuckoo filter cannot become saturated to the point of significantly degrading its {@code FPP}.
   * @see CuckooFilter#fpp()
   */
  public double currentFpp() {
    return table.currentFpp();
  }

  /**
   * Returns {@code true} if this filter contains no elements.
   *
   * @return {@code true} if this filter contains no elements
   * @see #sizeLong()
   */
  public boolean isEmpty() {
    return 0 == sizeLong();
  }

  /**
   * Returns {@code true} if {@code f} is compatible with {@code this} filter. {@code f} is
   * considered compatible if {@code this} filter can use it in combinatoric operations (e.g. {@link
   * #addAll(ProbabilisticFilter)}).
   *
   * @param f The filter to check for compatibility.
   * @return {@code true} if {@code f} is compatible with {@code this} filter.
   */
  public boolean isCompatible(ProbabilisticFilter<E> f) {
    checkNotNull(f);

    return (this != f)
            && (f instanceof CuckooFilter)
            && (this.table.isCompatible(((CuckooFilter) f).table))
            && (this.cuckooStrategy.equals(((CuckooFilter) f).cuckooStrategy))
            && (this.funnel.equals(((CuckooFilter) f).funnel));
  }

  @Override
  public boolean equals(@Nullable Object object) {
    if (object == this) {
      return true;
    }
    if (object instanceof CuckooFilter) {
      CuckooFilter<?> that = (CuckooFilter<?>) object;
      return this.funnel.equals(that.funnel)
              && this.cuckooStrategy.equals(that.cuckooStrategy)
              && this.table.equals(that.table)
              && this.cuckooStrategy.equivalent(this.table, that.table)
              ;
    }
    return false;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(funnel, cuckooStrategy, table);
  }

  private Object writeReplace() {
    return new SerialForm<E>(this);
  }

  /**
   * Returns the size in bits of the underlying cuckoo table data structure.
   */
  @VisibleForTesting
  long bitSize() {
    return table.bitSize();
  }

  /**
   * Writes this cuckoo filter to an output stream, with a custom format (not Java serialization).
   * This has been measured to save at least 400 bytes compared to regular serialization. <p/>
   * <p>
   * Use {@link #readFrom(InputStream, Funnel)} to reconstruct the written CuckooFilter.
   */
  public void writeTo(OutputStream out) throws IOException {
    /*
     * Serial form:
     * 1 signed byte for the strategy
     * 1 IEEE 754 floating-point double, the expected FPP
     * 1 big endian long, the number of entries
     * 1 big endian long, the checksum of entries
     * 1 big endian long for the number of buckets
     * 1 big endian int for the number of entries per bucket
     * 1 big endian int for the fingerprint size in bits
     * 1 big endian int, the number of longs in the filter table's data
     * N big endian longs of the filter table's data
     */
    DataOutputStream dout = new DataOutputStream(out);
    dout.writeByte(SignedBytes.checkedCast(cuckooStrategy.ordinal()));
    dout.writeDouble(fpp);
    dout.writeLong(table.size());
    dout.writeLong(table.checksum());
    dout.writeLong(table.numBuckets());
    dout.writeInt(table.numEntriesPerBucket());
    dout.writeInt(table.numBitsPerEntry());
    dout.writeInt(table.data().length);

    for (long value : table.data()) {
      dout.writeLong(value);
    }
  }

  @Override
  public String toString() {
    return "CuckooFilter{" +
            "table=" + table +
            ", funnel=" + funnel +
            ", strategy=" + cuckooStrategy +
            ", capacity=" + capacity() +
            ", fpp=" + fpp +
            ", currentFpp=" + currentFpp() +
            ", size=" + sizeLong() +
            '}';
  }

  private void checkCompatibility(ProbabilisticFilter<E> f, String verb) {
    checkArgument(f instanceof CuckooFilter, "Cannot" + verb + " a " +
            this.getClass().getSimpleName() + " with a " + f.getClass().getSimpleName());
    checkArgument(this.isCompatible(f), "Cannot " + verb + " incompatible filters. " +
            this.getClass().getSimpleName() + " instances must have equivalent funnels; the same " +
            "strategy; and the same number of buckets, entries per bucket, and bits per entry.");
  }

  private static class SerialForm<T> implements Serializable {
    private static final long serialVersionUID = 1;
    final long[] data;
    final long size;
    final long checksum;
    final long numBuckets;
    final int numEntriesPerBucket;
    final int numBitsPerEntry;
    final Funnel<? super T> funnel;
    final CuckooStrategy cuckooStrategy;
    final double fpp;

    SerialForm(CuckooFilter<T> filter) {
      this.data = filter.table.data();
      this.numBuckets = filter.table.numBuckets();
      this.numEntriesPerBucket = filter.table.numEntriesPerBucket();
      this.numBitsPerEntry = filter.table.numBitsPerEntry();
      this.size = filter.table.size();
      this.checksum = filter.table.checksum();
      this.funnel = filter.funnel;
      this.cuckooStrategy = filter.cuckooStrategy;
      this.fpp = filter.fpp;
    }

    Object readResolve() {
      return new CuckooFilter<T>(
              new CuckooTable(data, size, checksum, numBuckets, numEntriesPerBucket, numBitsPerEntry),
              funnel, cuckooStrategy, fpp);
    }
  }

}
