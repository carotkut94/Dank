package me.saket.dank.data;

import static me.saket.dank.data.SubredditSubscription.TABLE_NAME;
import static rx.Observable.just;

import android.content.Context;
import android.support.annotation.CheckResult;
import android.support.annotation.NonNull;

import com.squareup.sqlbrite.BriteDatabase;

import net.dean.jraw.models.Subreddit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import me.saket.dank.R;
import me.saket.dank.data.SubredditSubscription.PendingState;
import me.saket.dank.di.Dank;
import rx.Completable;
import rx.Observable;
import rx.functions.Action1;
import timber.log.Timber;

/**
 * Manages:
 * <p>
 * - Fetching user's subscription (cached or fresh or both)
 * - Subscribing
 * - Un-subscribing.
 * - Hiding subscriptions.
 */
public class SubredditSubscriptionManager {

    private Context appContext;
    private BriteDatabase database;
    private DankRedditClient dankRedditClient;

    public SubredditSubscriptionManager(Context appContext, BriteDatabase database, DankRedditClient dankRedditClient) {
        this.appContext = appContext;
        this.database = database;
        this.dankRedditClient = dankRedditClient;
    }

    /**
     * Gets user's subscriptions from the database.
     *
     * @param searchQuery Can be empty, but not null.
     */
    public Observable<List<SubredditSubscription>> search(String searchQuery, boolean includeHidden) {
        String getQuery = includeHidden
                ? SubredditSubscription.QUERY_SEARCH_ALL_SUBSCRIBED_INCLUDING_HIDDEN
                : SubredditSubscription.QUERY_SEARCH_ALL_SUBSCRIBED_EXCLUDING_HIDDEN;

        return database
                .createQuery(TABLE_NAME, getQuery, "%" + searchQuery + "%")
                .mapToList(SubredditSubscription.MAPPER)
                .flatMap(filteredSubs -> {
                    if (filteredSubs.isEmpty()) {
                        // Check if the database is empty and fetch fresh subscriptions from remote if needed.
                        // TODO: 15/04/17 What happens if both local and remote subscriptions are empty?
                        return database
                                .createQuery(TABLE_NAME, SubredditSubscription.QUERY_GET_ALL)
                                .mapToList(SubredditSubscription.MAPPER)
                                .first()
                                .flatMap(localSubs -> localSubs.isEmpty()
                                        ? fetchRemoteSubscriptions(localSubs).doOnNext(Dank.subscriptionManager().saveSubscriptionsToDatabase())
                                        : just(filteredSubs)
                                );
                    } else {
                        return just(filteredSubs);
                    }
                })
                .map(filteredSubs -> {
                    // Move Frontpage and Popular to the top.
                    String frontpageSubName = appContext.getString(R.string.frontpage_subreddit_name);
                    String popularSubName = appContext.getString(R.string.popular_subreddit_name);

                    SubredditSubscription frontpageSub = null;
                    SubredditSubscription popularSub = null;

                    for (int i = filteredSubs.size() - 1; i >= 0; i--) {
                        SubredditSubscription subscription = filteredSubs.get(i);
                        if (frontpageSub == null && subscription.name().equalsIgnoreCase(frontpageSubName)) {
                            filteredSubs.remove(i);
                            frontpageSub = subscription;

                        } else if (popularSub == null && subscription.name().equalsIgnoreCase(popularSubName)) {
                            // Found frontpage!
                            filteredSubs.remove(i);
                            popularSub = subscription;
                        }
                    }

                    if (frontpageSub != null) {
                        filteredSubs.add(0, frontpageSub);
                    }
                    if (popularSub != null) {
                        filteredSubs.add(1, popularSub);
                    }
                    return filteredSubs;
                });
    }

    @CheckResult
    public Completable subscribe(String subredditName) {
        return Completable.fromAction(() -> {
            SubredditSubscription subscription = SubredditSubscription.create(subredditName, PendingState.PENDING_SUBSCRIBE, false);
            database.update(TABLE_NAME, subscription.toContentValues(), SubredditSubscription.WHERE_NAME, subscription.name());

            dispatchSyncWithRedditJob();
        });
    }

    @CheckResult
    public Completable unsubscribe(SubredditSubscription subscription) {
        return Completable.fromAction(() -> {
            SubredditSubscription updated = SubredditSubscription.create(subscription.name(), PendingState.PENDING_UNSUBSCRIBE, subscription.isHidden());
            database.update(TABLE_NAME, updated.toContentValues(), SubredditSubscription.WHERE_NAME, subscription.name());

            dispatchSyncWithRedditJob();
        });
    }

    @CheckResult
    public Completable setHidden(SubredditSubscription subscription, boolean hidden) {
        return Completable.fromAction(() -> {
            if (subscription.pendingState() == PendingState.PENDING_UNSUBSCRIBE) {
                // When a subreddit gets marked for removal, the user should have not been able to toggle its hidden status.
                throw new IllegalStateException("Subreddit is marked for removal. Should have not reached here: " + subscription);
            }

            SubredditSubscription updated = SubredditSubscription.create(subscription.name(), subscription.pendingState(), hidden);
            database.update(TABLE_NAME, updated.toContentValues(), SubredditSubscription.WHERE_NAME, subscription.name());
            dispatchSyncWithRedditJob();
        });
    }

    /**
     * Removes all subreddit subscriptions.
     */
    @CheckResult
    public Completable removeAll() {
        return Completable.fromAction(() -> database.delete(TABLE_NAME, null));
    }

    private void dispatchSyncWithRedditJob() {
        // TODO: 15/04/17 Sync with Reddit.
    }

// ======== REMOTE SUBREDDITS ======== //

    private Observable<List<SubredditSubscription>> fetchRemoteSubscriptions(List<SubredditSubscription> localSubscriptions) {
        return (dankRedditClient.isUserLoggedIn() ? loggedInUserSubreddits() : just(loggedOutSubreddits()))
                .map(remoteSubNames -> {
                    Timber.i("Getting remote subs");

                    // So we've received subreddits from the server. Before replacing our database table with these,
                    // we must insert pending-subscribe items and remove pending-unsubscribe items which haven't
                    // synced yet.
                    HashMap<String, SubredditSubscription> localSubsMap = new HashMap<>(localSubscriptions.size());
                    for (SubredditSubscription localSub : localSubscriptions) {
                        localSubsMap.put(localSub.name(), localSub);
                    }

                    // Construct a new list of subs based on the remote subreddits.
                    List<SubredditSubscription> finalSubreddits = new ArrayList<>(remoteSubNames.size());

                    for (String remoteSubName : remoteSubNames) {
                        if (localSubsMap.containsKey(remoteSubName)) {
                            // We already have this subreddit.
                            SubredditSubscription localCopy = localSubsMap.get(remoteSubName);

                            if (!localCopy.isUnsubscribePending()) {
                                SubredditSubscription stateClearedCopy = localCopy.toBuilder()
                                        .pendingState(PendingState.NONE)
                                        .build();
                                finalSubreddits.add(stateClearedCopy);
                            }

                        } else {
                            // New subreddit. User must have subscribed to this subreddit the website or another app (hopefully not).
                            finalSubreddits.add(SubredditSubscription.create(remoteSubName, PendingState.NONE, false));
                        }
                    }
                    return finalSubreddits;
                });
    }

    @NonNull
    private Observable<List<String>> loggedInUserSubreddits() {
        return Observable.fromCallable(() -> {
            List<Subreddit> remoteSubs = dankRedditClient.userSubredditsPaginator().accumulateMergedAllSorted();
            List<String> remoteSubNames = new ArrayList<>(remoteSubs.size());
            for (Subreddit subreddit : remoteSubs) {
                remoteSubNames.add(subreddit.getDisplayName());
            }

            // Add frontpage and /r/popular.
            String frontpageSub = appContext.getString(R.string.frontpage_subreddit_name);
            remoteSubNames.add(0, frontpageSub);

            String popularSub = appContext.getString(R.string.popular_subreddit_name);
            if (!remoteSubNames.contains(popularSub) && !remoteSubNames.contains(popularSub.toLowerCase(Locale.ENGLISH))) {
                remoteSubNames.add(1, popularSub);
            }

            return remoteSubNames;
        });
    }

    private List<String> loggedOutSubreddits() {
        return Arrays.asList(appContext.getResources().getStringArray(R.array.default_subreddits));
    }

    /**
     * Replace all items in the database with a new list of subscriptions.
     */
    @NonNull
    private Action1<List<SubredditSubscription>> saveSubscriptionsToDatabase() {
        return newSubscriptions -> {
            Timber.i("Saving to DB");

            try (BriteDatabase.Transaction transaction = database.newTransaction()) {
                database.delete(TABLE_NAME, null);

                for (SubredditSubscription freshSubscription : newSubscriptions) {
                    database.insert(TABLE_NAME, freshSubscription.toContentValues());
                }

                transaction.markSuccessful();
            }
        };
    }

}