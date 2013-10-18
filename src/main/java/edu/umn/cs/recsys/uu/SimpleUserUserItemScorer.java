package edu.umn.cs.recsys.uu;

import it.unimi.dsi.fastutil.doubles.*;
import it.unimi.dsi.fastutil.longs.*;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import org.grouplens.lenskit.basic.AbstractItemScorer;
import org.grouplens.lenskit.cursors.Cursors;
import org.grouplens.lenskit.data.dao.ItemEventDAO;
import org.grouplens.lenskit.data.dao.UserEventDAO;
import org.grouplens.lenskit.data.event.Rating;
import org.grouplens.lenskit.data.history.History;
import org.grouplens.lenskit.data.history.RatingVectorUserHistorySummarizer;
import org.grouplens.lenskit.data.history.UserHistory;
import org.grouplens.lenskit.vectors.ImmutableSparseVector;
import org.grouplens.lenskit.vectors.MutableSparseVector;
import org.grouplens.lenskit.vectors.SparseVector;
import org.grouplens.lenskit.vectors.VectorEntry;
import org.grouplens.lenskit.vectors.similarity.CosineVectorSimilarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;
import java.awt.*;
import java.util.Collections;
import java.util.Map;

/**
 * User-user item scorer.
 * @author <a href="http://www.grouplens.org">GroupLens Research</a>
 */
public class SimpleUserUserItemScorer extends AbstractItemScorer {
    private final UserEventDAO userDao;
    private final ItemEventDAO itemDao;

    private static final Logger logger = LoggerFactory.getLogger("uu-assignment");

    @Inject
    public SimpleUserUserItemScorer(UserEventDAO udao, ItemEventDAO idao) {
        userDao = udao;
        itemDao = idao;
    }

    @Override
    public void score(long user, @Nonnull MutableSparseVector scores) {
        // Get the user's rating and mean-centered rating vectors
        SparseVector userVector = getUserRatingVector(user);
        SparseVector userMeanVector = getMeanCenteredVector(userVector);

        // Get the list of items this user has rated
        LongSortedSet items = userVector.keyDomain();

        // Map scores to lists of users who shared the similarity score
        Double2ObjectArrayMap<LongList> similarityMap = new Double2ObjectArrayMap<LongList>();


        // For each item, get a list of users who rated this item
        LongSet usersForItem = new LongLinkedOpenHashSet();
        LongSet users = new LongLinkedOpenHashSet();
        for (long i : items) {
            usersForItem = itemDao.getUsersForItem(i);

            // Iterate over the users who have rated this item.  If we've
            // already seen a user on a previous item, disregard and continue.
            for (long u : usersForItem) {
                if (users.contains(u) || u == user) continue;

                // Add this user to the set of users we've already seen
                users.add(u);

                // Get the other user's mean-centered vector to calculate similarity
                SparseVector otherMeanCenteredVector = getMeanCenteredVector(getUserRatingVector(u));

                double sim = similarity(userMeanVector.immutable(), otherMeanCenteredVector.immutable());

                if(similarityMap.get(sim) == null)
                    similarityMap.put(sim, new LongArrayList());

                // Update the user list for this similarity score
                LongList list = similarityMap.get(sim);
                list.add(u);
                similarityMap.put(sim, list);
            }
        }

        // Get a sorted (descending) list of similarities
        DoubleArrayList sorted = new DoubleArrayList(similarityMap.keySet());
        Collections.sort(sorted, DoubleComparators.OPPOSITE_COMPARATOR);

        // Get top 30 similar users
        ObjectList<MutableSparseVector> top30Neighbors = new ObjectArrayList<MutableSparseVector>(30);
        DoubleListIterator it = sorted.iterator();
        while(it.hasNext() && top30Neighbors.size() < 30) {
            double similarity = it.next();
            for(long u : similarityMap.get(similarity)) {
                SparseVector v = getUserRatingVector(u);
                top30Neighbors.add(v.mutableCopy());
            }
        }


        // This is the loop structure to iterate over items to score
        for (VectorEntry e: scores.fast(VectorEntry.State.EITHER)) {
            // 1. For each neighbor:
            //      sum += (similarity * offset for item i)
            double sumOfRatings = 0.0;
            double sumOfSimilarities = 0.0;
            double sim = 0.0;
            double rating = 0;
            double ratingOffset = 0.0;
            double meanRating = 0.0;
            for(MutableSparseVector v : top30Neighbors) {
                // Calculate similarity, mean, and offset
                sim = similarity(userMeanVector.immutable(), getMeanCenteredVector(v).immutable());
                meanRating = v.mean();

                // Assign the mean to offset if this neighbor hasn't rated the item
                if (!v.containsKey(e.getKey()))
                    rating = meanRating;
                else {
                    rating = v.get(e.getKey());
                    sumOfSimilarities += Math.abs(sim);
                }


                ratingOffset = rating - meanRating;

                sumOfRatings += sim * ratingOffset;
            }

            double p = userVector.mean() + (sumOfRatings / sumOfSimilarities);
//            logger.warn(Double.toString(p));
            scores.set(e.getKey(), p);
        }
    }


    /**
     * Get a user's rating vector.
     * @param user The user ID.
     * @return The rating vector.
     */
    private SparseVector getUserRatingVector(long user) {
        UserHistory<Rating> history = userDao.getEventsForUser(user, Rating.class);
        if (history == null) {
//            history = History.forUser(user);
        }
        return RatingVectorUserHistorySummarizer.makeRatingVector(history);
    }

    /**
     * Computes similarity between 2 vectors
     * @param v1 The first vector
     * @param v2 The second vector
     */
    private double similarity(ImmutableSparseVector v1, ImmutableSparseVector v2) {
        CosineVectorSimilarity sim = new CosineVectorSimilarity();
        return sim.similarity(v1, v2);
    }

    /**
     * Calculates the mean for a vector.
     * @param vector
     * @return
     */
    private SparseVector getMeanCenteredVector(SparseVector vector) {
        // Calculate the vector mean
        double mean = vector.mean();

        // Create a new vector where each entry is the value - mean
        MutableSparseVector meanCenteredVector = vector.mutableCopy();
        for (VectorEntry e : meanCenteredVector)
            meanCenteredVector.set(e.getKey(), e.getValue() - mean);

        return meanCenteredVector;
    }
}
