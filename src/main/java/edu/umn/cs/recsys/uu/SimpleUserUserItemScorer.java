package edu.umn.cs.recsys.uu;

import it.unimi.dsi.fastutil.doubles.*;
import it.unimi.dsi.fastutil.longs.*;
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

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;
import java.awt.*;
import java.util.Collections;

/**
 * User-user item scorer.
 * @author <a href="http://www.grouplens.org">GroupLens Research</a>
 */
public class SimpleUserUserItemScorer extends AbstractItemScorer {
    private final UserEventDAO userDao;
    private final ItemEventDAO itemDao;

    @Inject
    public SimpleUserUserItemScorer(UserEventDAO udao, ItemEventDAO idao) {
        userDao = udao;
        itemDao = idao;
    }

    @Override
    public void score(long user, @Nonnull MutableSparseVector scores) {
        SparseVector userVector = getUserRatingVector(user);
        SparseVector meanVector = getMeanCenteredVector(userVector);



        LongSortedSet items = userVector.keyDomain();
        Double2ObjectArrayMap<SparseVector> simMap = new Double2ObjectArrayMap<SparseVector>();


        // For each item, get a list of users who rated this item
        LongSet users = new LongLinkedOpenHashSet();
        LongSet usersForItem = new LongLinkedOpenHashSet();
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

                double sim = similarity(meanVector.immutable(), otherMeanCenteredVector.immutable());

                simMap.put(sim, otherMeanCenteredVector);
            }
        }

        DoubleArrayList sorted = new DoubleArrayList(simMap.keySet());
        Collections.sort(sorted, DoubleComparators.OPPOSITE_COMPARATOR);


        // TODO Score items for this user using user-user collaborative filtering

        // This is the loop structure to iterate over items to score
        for (VectorEntry e: scores.fast(VectorEntry.State.EITHER)) {

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

    private void topNSimilar(long uid, long n){
        Long2ObjectMap<SparseVector> simMap = new Long2ObjectOpenHashMap<SparseVector>();


    }

    /**
     * Calculates the mean for a vector.
     * @param vector
     * @return
     */
    private SparseVector getMeanCenteredVector(SparseVector vector) {
        // Calculate the vector mean
        double mean = 0.0;
        for(double r : vector.values())
            mean += r;
        mean /= (double)vector.size();

        // Create a new vector where each entry is the value - mean
        MutableSparseVector meanCenteredVector = vector.mutableCopy();
        for (VectorEntry e : meanCenteredVector)
            meanCenteredVector.set(e.getKey(), e.getValue() - mean);


        return meanCenteredVector;
    }
}
