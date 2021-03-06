package com.tv.inovelui.ui.view;

import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.support.annotation.ColorInt;
import android.support.v17.leanback.transition.TransitionHelper;
import android.support.v17.leanback.transition.TransitionListener;
import android.support.v17.leanback.widget.BrowseFrameLayout;
import android.support.v17.leanback.widget.InvisibleRowPresenter;
import android.support.v17.leanback.widget.ListRow;
import android.support.v17.leanback.widget.ObjectAdapter;
import android.support.v17.leanback.widget.OnItemViewClickedListener;
import android.support.v17.leanback.widget.OnItemViewSelectedListener;
import android.support.v17.leanback.widget.PageRow;
import android.support.v17.leanback.widget.Presenter;
import android.support.v17.leanback.widget.PresenterSelector;
import android.support.v17.leanback.widget.Row;
import android.support.v17.leanback.widget.RowHeaderPresenter;
import android.support.v17.leanback.widget.RowPresenter;
import android.support.v17.leanback.widget.ScaleFrameLayout;
import android.support.v17.leanback.widget.TitleViewAdapter;
import android.support.v17.leanback.widget.VerticalGridView;
import android.support.v4.view.ViewCompat;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.tv.inovelui.R;

import java.util.HashMap;
import java.util.Map;

import static android.support.v7.widget.RecyclerView.NO_POSITION;

/**
 * 功能描述：
 * 开发状况：正在开发中
 * 开发作者：黎丝军
 * 开发时间：2017/5/23- 11:38
 */

public class TvBrowseFragment extends TvBaseFragment {

    // BUNDLE attribute for saving header show/hide status when backstack is used:
    static final String HEADER_STACK_INDEX = "headerStackIndex";
    // BUNDLE attribute for saving header show/hide status when backstack is not used:
    static final String HEADER_SHOW = "headerShow";
    private static final String IS_PAGE_ROW = "isPageRow";
    private static final String CURRENT_SELECTED_POSITION = "currentSelectedPosition";

    final class BackStackListener implements FragmentManager.OnBackStackChangedListener {
        int mLastEntryCount;
        int mIndexOfHeadersBackStack;

        BackStackListener() {
            mLastEntryCount = getFragmentManager().getBackStackEntryCount();
            mIndexOfHeadersBackStack = -1;
        }

        void load(Bundle savedInstanceState) {
            if (savedInstanceState != null) {
                mIndexOfHeadersBackStack = savedInstanceState.getInt(HEADER_STACK_INDEX, -1);
                mShowingHeaders = mIndexOfHeadersBackStack == -1;
            } else {
                if (!mShowingHeaders) {
                    getFragmentManager().beginTransaction()
                            .addToBackStack(mWithHeadersBackStackName).commit();
                }
            }
        }

        void save(Bundle outState) {
            outState.putInt(HEADER_STACK_INDEX, mIndexOfHeadersBackStack);
        }


        @Override
        public void onBackStackChanged() {
            if (getFragmentManager() == null) {
                Log.w(TAG, "getFragmentManager() is null, stack:", new Exception());
                return;
            }
            int count = getFragmentManager().getBackStackEntryCount();
            // if backstack is growing and last pushed entry is "headers" backstack,
            // remember the index of the entry.
            if (count > mLastEntryCount) {
                FragmentManager.BackStackEntry entry = getFragmentManager().getBackStackEntryAt(count - 1);
                if (mWithHeadersBackStackName.equals(entry.getName())) {
                    mIndexOfHeadersBackStack = count - 1;
                }
            } else if (count < mLastEntryCount) {
                // if popped "headers" backstack, initiate the show header transition if needed
                if (mIndexOfHeadersBackStack >= count) {
                    if (!isHeadersDataReady()) {
                        // if main fragment was restored first before BrowseFragment's adapter gets
                        // restored: don't start header transition, but add the entry back.
                        getFragmentManager().beginTransaction()
                                .addToBackStack(mWithHeadersBackStackName).commit();
                        return;
                    }
                    mIndexOfHeadersBackStack = -1;
                    if (!mShowingHeaders) {
                        startHeadersTransitionInternal(true);
                    }
                }
            }
            mLastEntryCount = count;
        }
    }

    /**
     * Listener for transitions between browse headers and rows.
     */
    public static class BrowseTransitionListener {
        /**
         * Callback when headers transition starts.
         *
         * @param withHeaders True if the transition will result in headers
         *        being shown, false otherwise.
         */
        public void onHeadersTransitionStart(boolean withHeaders) {
        }
        /**
         * Callback when headers transition stops.
         *
         * @param withHeaders True if the transition will result in headers
         *        being shown, false otherwise.
         */
        public void onHeadersTransitionStop(boolean withHeaders) {
        }
    }

    private class SetSelectionRunnable implements Runnable {
        static final int TYPE_INVALID = -1;
        static final int TYPE_INTERNAL_SYNC = 0;
        static final int TYPE_USER_REQUEST = 1;

        private int mPosition;
        private int mType;
        private boolean mSmooth;

        SetSelectionRunnable() {
            reset();
        }

        void post(int position, int type, boolean smooth) {
            // Posting the set selection, rather than calling it immediately, prevents an issue
            // with adapter changes.  Example: a row is added before the current selected row;
            // first the fast lane view updates its selection, then the rows fragment has that
            // new selection propagated immediately; THEN the rows view processes the same adapter
            // change and moves the selection again.
            if (type >= mType) {
                mPosition = position;
                mType = type;
                mSmooth = smooth;
                mBrowseFrame.removeCallbacks(this);
                mBrowseFrame.post(this);
            }
        }

        @Override
        public void run() {
            setSelection(mPosition, mSmooth);
            reset();
        }

        private void reset() {
            mPosition = -1;
            mType = TYPE_INVALID;
            mSmooth = false;
        }
    }

    /**
     * Possible set of actions that {@link TvBrowseFragment} exposes to clients. Custom
     * fragments can interact with {@link TvBrowseFragment} using this interface.
     */
    public interface FragmentHost {
        /**
         * Fragments are required to invoke this callback once their view is created
         * inside {@link Fragment#onViewCreated} method. {@link TvBrowseFragment} starts the entrance
         * animation only after receiving this callback. Failure to invoke this method
         * will lead to fragment not showing up.
         *
         * @param fragmentAdapter {@link TvBrowseFragment.MainFragmentAdapter} used by the current fragment.
         */
        void notifyViewCreated(TvBrowseFragment.MainFragmentAdapter fragmentAdapter);

        /**
         * Fragments mapped to {@link PageRow} are required to invoke this callback once their data
         * is created for transition, the entrance animation only after receiving this callback.
         * Failure to invoke this method will lead to fragment not showing up.
         *
         * @param fragmentAdapter {@link TvBrowseFragment.MainFragmentAdapter} used by the current fragment.
         */
        void notifyDataReady(TvBrowseFragment.MainFragmentAdapter fragmentAdapter);

        /**
         * Show or hide title view in {@link TvBrowseFragment} for fragments mapped to
         * {@link PageRow}.  Otherwise the request is ignored, in that case TvBrowseFragment is fully
         * in control of showing/hiding title view.
         * <p>
         * When HeadersFragment is visible, TvBrowseFragment will hide search affordance view if
         * there are other focusable rows above currently focused row.
         *
         * @param show Boolean indicating whether or not to show the title view.
         */
        void showTitleView(boolean show);
    }

    /**
     * Default implementation of {@link TvBrowseFragment.FragmentHost} that is used only by
     * {@link TvBrowseFragment}.
     */
    private final class FragmentHostImpl implements TvBrowseFragment.FragmentHost {
        boolean mShowTitleView = true;
        boolean mDataReady = false;

        FragmentHostImpl() {
        }

        @Override
        public void notifyViewCreated(TvBrowseFragment.MainFragmentAdapter fragmentAdapter) {
            performPendingStates();
        }

        @Override
        public void notifyDataReady(TvBrowseFragment.MainFragmentAdapter fragmentAdapter) {
            mDataReady = true;

            // If fragment host is not the currently active fragment (in TvBrowseFragment), then
            // ignore the request.
            if (mMainFragmentAdapter == null || mMainFragmentAdapter.getFragmentHost() != this) {
                return;
            }

            // We only honor showTitle request for PageRows.
            if (!mIsPageRow) {
                return;
            }

            performPendingStates();
        }

        @Override
        public void showTitleView(boolean show) {
            mShowTitleView = show;

            // If fragment host is not the currently active fragment (in TvBrowseFragment), then
            // ignore the request.
            if (mMainFragmentAdapter == null || mMainFragmentAdapter.getFragmentHost() != this) {
                return;
            }

            // We only honor showTitle request for PageRows.
            if (!mIsPageRow) {
                return;
            }

            updateTitleViewVisibility();
        }
    }

    /**
     * Interface that defines the interaction between {@link TvBrowseFragment} and it's main
     * content fragment. The key method is {@link TvBrowseFragment.MainFragmentAdapter#getFragment()},
     * it will be used to get the fragment to be shown in the content section. Clients can
     * provide any implementation of fragment and customize it's interaction with
     * {@link TvBrowseFragment} by overriding the necessary methods.
     *
     * <p>
     * Clients are expected to provide
     * an instance of {@link TvBrowseFragment.MainFragmentAdapterRegistry} which will be responsible for providing
     * implementations of {@link TvBrowseFragment.MainFragmentAdapter} for given content types. Currently
     * we support different types of content - {@link ListRow}, {@link PageRow} or any subtype
     * of {@link Row}. We provide an out of the box adapter implementation for any rows other than
     * {@link PageRow} - {@link android.support.v17.leanback.app.RowsFragment.MainFragmentAdapter}.
     *
     * <p>
     * {@link PageRow} is intended to give full flexibility to developers in terms of Fragment
     * design. Users will have to provide an implementation of {@link TvBrowseFragment.MainFragmentAdapter}
     * and provide that through {@link TvBrowseFragment.MainFragmentAdapterRegistry}.
     * {@link TvBrowseFragment.MainFragmentAdapter} implementation can supply any fragment and override
     * just those interactions that makes sense.
     */
    public static class MainFragmentAdapter<T extends Fragment> {
        private boolean mScalingEnabled;
        private final T mFragment;
        TvBrowseFragment.FragmentHostImpl mFragmentHost;

        public MainFragmentAdapter(T fragment) {
            this.mFragment = fragment;
        }

        public final T getFragment() {
            return mFragment;
        }

        /**
         * Returns whether its scrolling.
         */
        public boolean isScrolling() {
            return false;
        }

        /**
         * Set the visibility of titles/hovercard of browse rows.
         */
        public void setExpand(boolean expand) {
        }

        /**
         * For rows that willing to participate entrance transition,  this function
         * hide views if afterTransition is true,  show views if afterTransition is false.
         */
        public void setEntranceTransitionState(boolean state) {
        }

        /**
         * Sets the window alignment and also the pivots for scale operation.
         */
        public void setAlignment(int windowAlignOffsetFromTop) {
        }

        /**
         * Callback indicating transition prepare start.
         */
        public boolean onTransitionPrepare() {
            return false;
        }

        /**
         * Callback indicating transition start.
         */
        public void onTransitionStart() {
        }

        /**
         * Callback indicating transition end.
         */
        public void onTransitionEnd() {
        }

        /**
         * Returns whether row scaling is enabled.
         */
        public boolean isScalingEnabled() {
            return mScalingEnabled;
        }

        /**
         * Sets the row scaling property.
         */
        public void setScalingEnabled(boolean scalingEnabled) {
            this.mScalingEnabled = scalingEnabled;
        }

        /**
         * Returns the current host interface so that main fragment can interact with
         * {@link TvBrowseFragment}.
         */
        public final TvBrowseFragment.FragmentHost getFragmentHost() {
            return mFragmentHost;
        }

        void setFragmentHost(TvBrowseFragment.FragmentHostImpl fragmentHost) {
            this.mFragmentHost = fragmentHost;
        }
    }

    /**
     * Interface to be implemented by all fragments for providing an instance of
     * {@link TvBrowseFragment.MainFragmentAdapter}. Both {@link TvRowsFragment} and custom fragment provided
     * against {@link PageRow} will need to implement this interface.
     */
    public interface MainFragmentAdapterProvider {
        /**
         * Returns an instance of {@link TvBrowseFragment.MainFragmentAdapter} that {@link TvBrowseFragment}
         * would use to communicate with the target fragment.
         */
        TvBrowseFragment.MainFragmentAdapter getMainFragmentAdapter();
    }

    /**
     * Interface to be implemented by {@link TvRowsFragment} and it's subclasses for providing
     * an instance of {@link TvBrowseFragment.MainFragmentRowsAdapter}.
     */
    public interface MainFragmentRowsAdapterProvider {
        /**
         * Returns an instance of {@link TvBrowseFragment.MainFragmentRowsAdapter} that {@link TvBrowseFragment}
         * would use to communicate with the target fragment.
         */
        TvBrowseFragment.MainFragmentRowsAdapter getMainFragmentRowsAdapter();
    }

    /**
     * This is used to pass information to {@link TvRowsFragment} or its subclasses.
     * {@link TvBrowseFragment} uses this interface to pass row based interaction events to
     * the target fragment.
     */
    public static class MainFragmentRowsAdapter<T extends Fragment> {
        private final T mFragment;

        public MainFragmentRowsAdapter(T fragment) {
            if (fragment == null) {
                throw new IllegalArgumentException("Fragment can't be null");
            }
            this.mFragment = fragment;
        }

        public final T getFragment() {
            return mFragment;
        }
        /**
         * Set the visibility titles/hover of browse rows.
         */
        public void setAdapter(ObjectAdapter adapter) {
        }

        /**
         * Sets an item clicked listener on the fragment.
         */
        public void setOnItemViewClickedListener(OnItemViewClickedListener listener) {
        }

        /**
         * Sets an item selection listener.
         */
        public void setOnItemViewSelectedListener(OnItemViewSelectedListener listener) {
        }

        /**
         * Selects a Row and perform an optional task on the Row.
         */
        public void setSelectedPosition(int rowPosition,
                                        boolean smooth,
                                        final Presenter.ViewHolderTask rowHolderTask) {
        }

        /**
         * Selects a Row.
         */
        public void setSelectedPosition(int rowPosition, boolean smooth) {
        }

        /**
         * @return The position of selected row.
         */
        public int getSelectedPosition() {
            return 0;
        }

        /**
         * @param position Position of Row.
         * @return Row ViewHolder.
         */
        public RowPresenter.ViewHolder findRowViewHolderByPosition(int position) {
            return null;
        }
    }

    private boolean createMainFragment(ObjectAdapter adapter, int position) {
        Object item = null;
        if (adapter == null || adapter.size() == 0) {
            return false;
        } else {
            if (position < 0) {
                position = 0;
            } else if (position >= adapter.size()) {
                throw new IllegalArgumentException(
                        String.format("Invalid position %d requested", position));
            }
            item = adapter.get(position);
        }

        boolean oldIsPageRow = mIsPageRow;
        mIsPageRow = item instanceof PageRow;
        boolean swap;

        if (mMainFragment == null) {
            swap = true;
        } else {
            if (oldIsPageRow) {
                swap = true;
            } else {
                swap = mIsPageRow;
            }
        }

        if (swap) {
            mMainFragment = mMainFragmentAdapterRegistry.createFragment(item);
            if (!(mMainFragment instanceof MainFragmentAdapterProvider)) {
                throw new IllegalArgumentException(
                        "Fragment must implement MainFragmentAdapterProvider");
            }

            mMainFragmentAdapter = ((MainFragmentAdapterProvider)mMainFragment)
                    .getMainFragmentAdapter();
            mMainFragmentAdapter.setFragmentHost(new FragmentHostImpl());
            if (!mIsPageRow) {
                if (mMainFragment instanceof MainFragmentRowsAdapterProvider) {
                    mMainFragmentRowsAdapter = ((MainFragmentRowsAdapterProvider)mMainFragment)
                            .getMainFragmentRowsAdapter();
                } else {
                    mMainFragmentRowsAdapter = null;
                }
                mIsPageRow = mMainFragmentRowsAdapter == null;
            } else {
                mMainFragmentRowsAdapter = null;
            }
        }

        return swap;
    }

    /**
     * Factory class responsible for creating fragment given the current item. {@link ListRow}
     * should returns {@link TvRowsFragment} or it's subclass whereas {@link PageRow}
     * can return any fragment class.
     */
    public abstract static class FragmentFactory<T extends Fragment> {
        public abstract T createFragment(Object row);
    }

    /**
     * FragmentFactory implementation for {@link ListRow}.
     */
    public static class ListRowFragmentFactory extends TvBrowseFragment.FragmentFactory<TvRowsFragment> {
        @Override
        public TvRowsFragment createFragment(Object row) {
            return new TvRowsFragment();
        }
    }

    /**
     * Registry class maintaining the mapping of {@link Row} subclasses to {@link TvBrowseFragment.FragmentFactory}.
     * BrowseRowFragment automatically registers {@link TvBrowseFragment.ListRowFragmentFactory} for
     * handling {@link ListRow}. Developers can override that and also if they want to
     * use custom fragment, they can register a custom {@link TvBrowseFragment.FragmentFactory}
     * against {@link PageRow}.
     */
    public final static class MainFragmentAdapterRegistry {
        private final Map<Class, TvBrowseFragment.FragmentFactory> mItemToFragmentFactoryMapping = new HashMap();
        private final static TvBrowseFragment.FragmentFactory sDefaultFragmentFactory = new TvBrowseFragment.ListRowFragmentFactory();

        public MainFragmentAdapterRegistry() {
            registerFragment(ListRow.class, sDefaultFragmentFactory);
        }

        public void registerFragment(Class rowClass, TvBrowseFragment.FragmentFactory factory) {
            mItemToFragmentFactoryMapping.put(rowClass, factory);
        }

        public Fragment createFragment(Object item) {
            if (item == null) {
                throw new IllegalArgumentException("Item can't be null");
            }

            TvBrowseFragment.FragmentFactory fragmentFactory = mItemToFragmentFactoryMapping.get(item.getClass());
            if (fragmentFactory == null && !(item instanceof PageRow)) {
                fragmentFactory = sDefaultFragmentFactory;
            }

            return fragmentFactory.createFragment(item);
        }
    }

    static final String TAG = "BrowseFragment";

    private static final String LB_HEADERS_BACKSTACK = "lbHeadersBackStack_";

    static boolean DEBUG = false;

    /** The headers fragment is enabled and shown by default. */
    public static final int HEADERS_ENABLED = 1;

    /** The headers fragment is enabled and hidden by default. */
    public static final int HEADERS_HIDDEN = 2;

    /** The headers fragment is disabled and will never be shown. */
    public static final int HEADERS_DISABLED = 3;

    private TvBrowseFragment.MainFragmentAdapterRegistry mMainFragmentAdapterRegistry =
            new TvBrowseFragment.MainFragmentAdapterRegistry();
    TvBrowseFragment.MainFragmentAdapter mMainFragmentAdapter;
    Fragment mMainFragment;
    TvHeaderFragment mHeadersFragment;
    private TvBrowseFragment.MainFragmentRowsAdapter mMainFragmentRowsAdapter;

    private ObjectAdapter mAdapter;
    private PresenterSelector mAdapterPresenter;
    private PresenterSelector mWrappingPresenterSelector;

    private int mHeadersState = HEADERS_ENABLED;
    private int mBrandColor = Color.TRANSPARENT;
    private boolean mBrandColorSet;

    BrowseFrameLayout mBrowseFrame;
    private ScaleFrameLayout mScaleFrameLayout;
    boolean mHeadersBackStackEnabled = true;
    String mWithHeadersBackStackName;
    boolean mShowingHeaders = true;
    boolean mCanShowHeaders = true;
    private int mContainerListMarginStart;
    private int mContainerListAlignTop;
    private boolean mMainFragmentScaleEnabled = true;
    OnItemViewSelectedListener mExternalOnItemViewSelectedListener;
    private OnItemViewClickedListener mOnItemViewClickedListener;
    private int mSelectedPosition = -1;
    private float mScaleFactor;
    boolean mIsPageRow;

    private PresenterSelector mHeaderPresenterSelector;
    private final TvBrowseFragment.SetSelectionRunnable mSetSelectionRunnable = new TvBrowseFragment.SetSelectionRunnable();

    // transition related:
    Object mSceneWithHeaders;
    Object mSceneWithoutHeaders;
    private Object mSceneAfterEntranceTransition;
    Object mHeadersTransition;
    TvBrowseFragment.BackStackListener mBackStackChangedListener;
    TvBrowseFragment.BrowseTransitionListener mBrowseTransitionListener;

    private static final String ARG_TITLE = TvBrowseFragment.class.getCanonicalName() + ".title";
    private static final String ARG_HEADERS_STATE =
            TvBrowseFragment.class.getCanonicalName() + ".headersState";

    /**
     * Creates arguments for a browse fragment.
     *
     * @param args The Bundle to place arguments into, or null if the method
     *        should return a new Bundle.
     * @param title The title of the TvBrowseFragment.
     * @param headersState The initial state of the headers of the
     *        TvBrowseFragment. Must be one of {@link #HEADERS_ENABLED}, {@link
     *        #HEADERS_HIDDEN}, or {@link #HEADERS_DISABLED}.
     * @return A Bundle with the given arguments for creating a TvBrowseFragment.
     */
    public static Bundle createArgs(Bundle args, String title, int headersState) {
        if (args == null) {
            args = new Bundle();
        }
        args.putString(ARG_TITLE, title);
        args.putInt(ARG_HEADERS_STATE, headersState);
        return args;
    }

    /**
     * Sets the brand color for the browse fragment. The brand color is used as
     * the primary color for UI elements in the browse fragment. For example,
     * the background color of the headers fragment uses the brand color.
     *
     * @param color The color to use as the brand color of the fragment.
     */
    public void setBrandColor(@ColorInt int color) {
        mBrandColor = color;
        mBrandColorSet = true;

        if (mHeadersFragment != null) {
            mHeadersFragment.setBackgroundColor(mBrandColor);
        }
    }

    /**
     * Returns the brand color for the browse fragment.
     * The default is transparent.
     */
    @ColorInt
    public int getBrandColor() {
        return mBrandColor;
    }

    /**
     * Wrapping app provided PresenterSelector to support InvisibleRowPresenter for SectionRow
     * DividerRow and PageRow.
     */
    private void createAndSetWrapperPresenter() {
        final PresenterSelector adapterPresenter = mAdapter.getPresenterSelector();
        if (adapterPresenter == null) {
            throw new IllegalArgumentException("Adapter.getPresenterSelector() is null");
        }
        if (adapterPresenter == mAdapterPresenter) {
            return;
        }
        mAdapterPresenter = adapterPresenter;

        Presenter[] presenters = adapterPresenter.getPresenters();
        final Presenter invisibleRowPresenter = new InvisibleRowPresenter();
        final Presenter[] allPresenters = new Presenter[presenters.length + 1];
        System.arraycopy(allPresenters, 0, presenters, 0, presenters.length);
        allPresenters[allPresenters.length - 1] = invisibleRowPresenter;
        mAdapter.setPresenterSelector(new PresenterSelector() {
            @Override
            public Presenter getPresenter(Object item) {
                Row row = (Row) item;
                if (row.isRenderedAsRowView()) {
                    return adapterPresenter.getPresenter(item);
                } else {
                    return invisibleRowPresenter;
                }
            }

            @Override
            public Presenter[] getPresenters() {
                return allPresenters;
            }
        });
    }

    /**
     * Sets the adapter containing the rows for the fragment.
     *
     * <p>The items referenced by the adapter must be be derived from
     * {@link Row}. These rows will be used by the rows fragment and the headers
     * fragment (if not disabled) to render the browse rows.
     *
     * @param adapter An ObjectAdapter for the browse rows. All items must
     *        derive from {@link Row}.
     */
    public void setAdapter(ObjectAdapter adapter) {
        mAdapter = adapter;
        createAndSetWrapperPresenter();
        if (getView() == null) {
            return;
        }
        replaceMainFragment(mSelectedPosition);

        if (adapter != null) {
            if (mMainFragmentRowsAdapter != null) {
                mMainFragmentRowsAdapter.setAdapter(new TvListRowDataAdapter(adapter));
            }
            mHeadersFragment.setAdapter(adapter);
        }
    }

    public final TvBrowseFragment.MainFragmentAdapterRegistry getMainFragmentRegistry() {
        return mMainFragmentAdapterRegistry;
    }

    /**
     * Returns the adapter containing the rows for the fragment.
     */
    public ObjectAdapter getAdapter() {
        return mAdapter;
    }

    /**
     * Sets an item selection listener.
     */
    public void setOnItemViewSelectedListener(OnItemViewSelectedListener listener) {
        mExternalOnItemViewSelectedListener = listener;
    }

    /**
     * Returns an item selection listener.
     */
    public OnItemViewSelectedListener getOnItemViewSelectedListener() {
        return mExternalOnItemViewSelectedListener;
    }

    /**
     * Get RowsFragment if it's bound to TvBrowseFragment or null if either TvBrowseFragment has
     * not been created yet or a different fragment is bound to it.
     *
     * @return RowsFragment if it's bound to TvBrowseFragment or null otherwise.
     */
    public TvRowsFragment getRowsFragment() {
        if (mMainFragment instanceof TvRowsFragment) {
            return (TvRowsFragment) mMainFragment;
        }

        return null;
    }

    /**
     * @return Current main fragment or null if not created.
     */
    public Fragment getMainFragment() {
        return mMainFragment;
    }

    /**
     * Get currently bound HeadersFragment or null if HeadersFragment has not been created yet.
     * @return Currently bound HeadersFragment or null if HeadersFragment has not been created yet.
     */
    public TvHeaderFragment getHeadersFragment() {
        return mHeadersFragment;
    }

    /**
     * Sets an item clicked listener on the fragment.
     * OnItemViewClickedListener will override {@link View.OnClickListener} that
     * item presenter sets during {@link Presenter#onCreateViewHolder(ViewGroup)}.
     * So in general, developer should choose one of the listeners but not both.
     */
    public void setOnItemViewClickedListener(OnItemViewClickedListener listener) {
        mOnItemViewClickedListener = listener;
        if (mMainFragmentRowsAdapter != null) {
            mMainFragmentRowsAdapter.setOnItemViewClickedListener(listener);
        }
    }

    /**
     * Returns the item Clicked listener.
     */
    public OnItemViewClickedListener getOnItemViewClickedListener() {
        return mOnItemViewClickedListener;
    }

    /**
     * Starts a headers transition.
     *
     * <p>This method will begin a transition to either show or hide the
     * headers, depending on the value of withHeaders. If headers are disabled
     * for this browse fragment, this method will throw an exception.
     *
     * @param withHeaders True if the headers should transition to being shown,
     *        false if the transition should result in headers being hidden.
     */
    public void startHeadersTransition(boolean withHeaders) {
        if (!mCanShowHeaders) {
            throw new IllegalStateException("Cannot start headers transition");
        }
        if (isInHeadersTransition() || mShowingHeaders == withHeaders) {
            return;
        }
        startHeadersTransitionInternal(withHeaders);
    }

    /**
     * Returns true if the headers transition is currently running.
     */
    public boolean isInHeadersTransition() {
        return mHeadersTransition != null;
    }

    /**
     * Returns true if headers are shown.
     */
    public boolean isShowingHeaders() {
        return mShowingHeaders;
    }

    /**
     * Sets a listener for browse fragment transitions.
     *
     * @param listener The listener to call when a browse headers transition
     *        begins or ends.
     */
    public void setBrowseTransitionListener(TvBrowseFragment.BrowseTransitionListener listener) {
        mBrowseTransitionListener = listener;
    }

    /**
     * @deprecated use {@link TvBrowseFragment#enableMainFragmentScaling(boolean)} instead.
     *
     * @param enable true to enable row scaling
     */
    @Deprecated
    public void enableRowScaling(boolean enable) {
        enableMainFragmentScaling(enable);
    }

    /**
     * Enables scaling of main fragment when headers are present. For the page/row fragment,
     * scaling is enabled only when both this method and
     * {@link TvBrowseFragment.MainFragmentAdapter#isScalingEnabled()} are enabled.
     *
     * @param enable true to enable row scaling
     */
    public void enableMainFragmentScaling(boolean enable) {
        mMainFragmentScaleEnabled = enable;
    }

    void startHeadersTransitionInternal(final boolean withHeaders) {
        if (getFragmentManager().isDestroyed()) {
            return;
        }
        if (!isHeadersDataReady()) {
            return;
        }
        mShowingHeaders = withHeaders;
        mMainFragmentAdapter.onTransitionPrepare();
        mMainFragmentAdapter.onTransitionStart();
        onExpandTransitionStart(!withHeaders, new Runnable() {
            @Override
            public void run() {
                mHeadersFragment.onTransitionPrepare();
                mHeadersFragment.onTransitionStart();
                createHeadersTransition();
                if (mBrowseTransitionListener != null) {
                    mBrowseTransitionListener.onHeadersTransitionStart(withHeaders);
                }
                TransitionHelper.runTransition(
                        withHeaders ? mSceneWithHeaders : mSceneWithoutHeaders, mHeadersTransition);
                if (mHeadersBackStackEnabled) {
                    if (!withHeaders) {
                        getFragmentManager().beginTransaction()
                                .addToBackStack(mWithHeadersBackStackName).commit();
                    } else {
                        int index = mBackStackChangedListener.mIndexOfHeadersBackStack;
                        if (index >= 0) {
                            FragmentManager.BackStackEntry entry = getFragmentManager().getBackStackEntryAt(index);
                            getFragmentManager().popBackStackImmediate(entry.getId(),
                                    FragmentManager.POP_BACK_STACK_INCLUSIVE);
                        }
                    }
                }
            }
        });
    }

    boolean isVerticalScrolling() {
        // don't run transition
        return mHeadersFragment.isScrolling() || mMainFragmentAdapter.isScrolling();
    }


    private final BrowseFrameLayout.OnFocusSearchListener mOnFocusSearchListener =
            new BrowseFrameLayout.OnFocusSearchListener() {
                @Override
                public View onFocusSearch(View focused, int direction) {
                    // if headers is running transition,  focus stays
                    if (mCanShowHeaders && isInHeadersTransition()) {
                        return focused;
                    }
                    if (DEBUG) Log.v(TAG, "onFocusSearch focused " + focused + " + direction " + direction);

                    if (getTitleView() != null && focused != getTitleView()
                            && direction == View.FOCUS_UP) {
                        return getTitleView();
                    }
                    if (getTitleView() != null && getTitleView().hasFocus()
                            && direction == View.FOCUS_DOWN) {
                        return mCanShowHeaders && mShowingHeaders
                                ? mHeadersFragment.getVerticalGridView() : mMainFragment.getView();
                    }

                    boolean isRtl = ViewCompat.getLayoutDirection(focused) == ViewCompat.LAYOUT_DIRECTION_RTL;
                    int towardStart = isRtl ? View.FOCUS_RIGHT : View.FOCUS_LEFT;
                    int towardEnd = isRtl ? View.FOCUS_LEFT : View.FOCUS_RIGHT;
                    if (mCanShowHeaders && direction == towardStart) {
                        if (isVerticalScrolling() || mShowingHeaders || !isHeadersDataReady()) {
                            return focused;
                        }
                        return mHeadersFragment.getVerticalGridView();
                    } else if (direction == towardEnd) {
                        if (isVerticalScrolling()) {
                            return focused;
                        } else if (mMainFragment != null && mMainFragment.getView() != null) {
                            return mMainFragment.getView();
                        }
                        return focused;
                    } else if (direction == View.FOCUS_DOWN && mShowingHeaders) {
                        // disable focus_down moving into PageFragment.
                        return focused;
                    } else {
                        return null;
                    }
                }
            };

    final boolean isHeadersDataReady() {
        return mAdapter != null && mAdapter.size() != 0;
    }

    private final BrowseFrameLayout.OnChildFocusListener mOnChildFocusListener =
            new BrowseFrameLayout.OnChildFocusListener() {

                @Override
                public boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
                    if (getChildFragmentManager().isDestroyed()) {
                        return true;
                    }
                    // Make sure not changing focus when requestFocus() is called.
                    if (mCanShowHeaders && mShowingHeaders) {
                        if (mHeadersFragment != null && mHeadersFragment.getView() != null
                                && mHeadersFragment.getView().requestFocus(
                                direction, previouslyFocusedRect)) {
                            return true;
                        }
                    }
                    if (mMainFragment != null && mMainFragment.getView() != null
                            && mMainFragment.getView().requestFocus(direction, previouslyFocusedRect)) {
                        return true;
                    }
                    return getTitleView() != null
                            && getTitleView().requestFocus(direction, previouslyFocusedRect);
                }

                @Override
                public void onRequestChildFocus(View child, View focused) {
                    if (getChildFragmentManager().isDestroyed()) {
                        return;
                    }
                    if (!mCanShowHeaders || isInHeadersTransition()) return;
                    int childId = child.getId();
                    if (childId == android.support.v17.leanback.R.id.browse_container_dock && mShowingHeaders) {
                        startHeadersTransitionInternal(false);
                    } else if (childId == android.support.v17.leanback.R.id.browse_headers_dock && !mShowingHeaders) {
                        startHeadersTransitionInternal(true);
                    }
                }
            };

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(CURRENT_SELECTED_POSITION, mSelectedPosition);
        outState.putBoolean(IS_PAGE_ROW, mIsPageRow);

        if (mBackStackChangedListener != null) {
            mBackStackChangedListener.save(outState);
        } else {
            outState.putBoolean(HEADER_SHOW, mShowingHeaders);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TypedArray ta = getActivity().obtainStyledAttributes(android.support.v17.leanback.R.styleable.LeanbackTheme);
        mContainerListMarginStart = (int) ta.getDimension(
                android.support.v17.leanback.R.styleable.LeanbackTheme_browseRowsMarginStart, getActivity().getResources()
                        .getDimensionPixelSize(android.support.v17.leanback.R.dimen.lb_browse_rows_margin_start));
        mContainerListAlignTop = (int) ta.getDimension(
                android.support.v17.leanback.R.styleable.LeanbackTheme_browseRowsMarginTop, getActivity().getResources()
                        .getDimensionPixelSize(android.support.v17.leanback.R.dimen.lb_browse_rows_margin_top));
        ta.recycle();

        readArguments(getArguments());

        if (mCanShowHeaders) {
            if (mHeadersBackStackEnabled) {
                mWithHeadersBackStackName = LB_HEADERS_BACKSTACK + this;
                mBackStackChangedListener = new TvBrowseFragment.BackStackListener();
                getFragmentManager().addOnBackStackChangedListener(mBackStackChangedListener);
                mBackStackChangedListener.load(savedInstanceState);
            } else {
                if (savedInstanceState != null) {
                    mShowingHeaders = savedInstanceState.getBoolean(HEADER_SHOW);
                }
            }
        }

        mScaleFactor = getResources().getFraction(android.support.v17.leanback.R.fraction.lb_browse_rows_scale, 1, 1);
    }

    @Override
    public void onDestroyView() {
        mMainFragmentRowsAdapter = null;
        mMainFragmentAdapter = null;
        mMainFragment = null;
        mHeadersFragment = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        if (mBackStackChangedListener != null) {
            getFragmentManager().removeOnBackStackChangedListener(mBackStackChangedListener);
        }
        super.onDestroy();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        if (getChildFragmentManager().findFragmentById(R.id.scale_frame) == null) {
            mHeadersFragment = new TvHeaderFragment();

            createMainFragment(mAdapter, mSelectedPosition);
            FragmentTransaction ft = getChildFragmentManager().beginTransaction()
                    .replace(android.support.v17.leanback.R.id.browse_headers_dock, mHeadersFragment);

            if (mMainFragment != null) {
                ft.replace(R.id.scale_frame, mMainFragment);
            } else {
                // Empty adapter used to guard against lazy adapter loading. When this
                // fragment is instantiated, mAdapter might not have the data or might not
                // have been set. In either of those cases mFragmentAdapter will be null.
                // This way we can maintain the invariant that mMainFragmentAdapter is never
                // null and it avoids doing null checks all over the code.
                mMainFragmentAdapter = new TvBrowseFragment.MainFragmentAdapter(null);
                mMainFragmentAdapter.setFragmentHost(new TvBrowseFragment.FragmentHostImpl());
            }

            ft.commit();
        } else {
            mHeadersFragment = (TvHeaderFragment) getChildFragmentManager()
                    .findFragmentById(R.id.browse_headers_dock);
            mMainFragment = getChildFragmentManager().findFragmentById(R.id.scale_frame);
            mMainFragmentAdapter = ((TvBrowseFragment.MainFragmentAdapterProvider)mMainFragment)
                    .getMainFragmentAdapter();
            mMainFragmentAdapter.setFragmentHost(new TvBrowseFragment.FragmentHostImpl());

            mIsPageRow = savedInstanceState != null
                    && savedInstanceState.getBoolean(IS_PAGE_ROW, false);

            mSelectedPosition = savedInstanceState != null
                    ? savedInstanceState.getInt(CURRENT_SELECTED_POSITION, 0) : 0;

            if (!mIsPageRow) {
                if (mMainFragment instanceof TvBrowseFragment.MainFragmentRowsAdapterProvider) {
                    mMainFragmentRowsAdapter = ((TvBrowseFragment.MainFragmentRowsAdapterProvider) mMainFragment)
                            .getMainFragmentRowsAdapter();
                } else {
                    mMainFragmentRowsAdapter = null;
                }
            } else {
                mMainFragmentRowsAdapter = null;
            }
        }

        mHeadersFragment.setHeadersGone(!mCanShowHeaders);
        if (mHeaderPresenterSelector != null) {
            mHeadersFragment.setPresenterSelector(mHeaderPresenterSelector);
        }
        mHeadersFragment.setAdapter(mAdapter);
        mHeadersFragment.setOnHeaderViewSelectedListener(mHeaderViewSelectedListener);
        mHeadersFragment.setOnHeaderClickedListener(mHeaderClickedListener);

        View root = inflater.inflate(R.layout.lb_browse_fragment, container, false);

        getProgressBarManager().setRootView((ViewGroup)root);

        mBrowseFrame = (BrowseFrameLayout) root.findViewById(R.id.browse_frame);
        mBrowseFrame.setOnChildFocusListener(mOnChildFocusListener);
        mBrowseFrame.setOnFocusSearchListener(mOnFocusSearchListener);

        installTitleView(inflater, mBrowseFrame, savedInstanceState);

        mScaleFrameLayout = (ScaleFrameLayout) root.findViewById(R.id.scale_frame);
        mScaleFrameLayout.setPivotX(0);
        mScaleFrameLayout.setPivotY(mContainerListAlignTop);

        setupMainFragment();

        if (mBrandColorSet) {
            mHeadersFragment.setBackgroundColor(mBrandColor);
        }

        mSceneWithHeaders = TransitionHelper.createScene(mBrowseFrame, new Runnable() {
            @Override
            public void run() {
                showHeaders(true);
            }
        });
        mSceneWithoutHeaders =  TransitionHelper.createScene(mBrowseFrame, new Runnable() {
            @Override
            public void run() {
                showHeaders(false);
            }
        });
        mSceneAfterEntranceTransition = TransitionHelper.createScene(mBrowseFrame, new Runnable() {
            @Override
            public void run() {
                setEntranceTransitionEndState();
            }
        });

        return root;
    }

    private void setupMainFragment() {
        if (mMainFragmentRowsAdapter != null) {
            if (mAdapter != null) {
                mMainFragmentRowsAdapter.setAdapter(new TvListRowDataAdapter(mAdapter));
            }
            mMainFragmentRowsAdapter.setOnItemViewSelectedListener(
                    new TvBrowseFragment.MainFragmentItemViewSelectedListener(mMainFragmentRowsAdapter));
            mMainFragmentRowsAdapter.setOnItemViewClickedListener(mOnItemViewClickedListener);
        }
    }

    @Override
    boolean isReadyForPrepareEntranceTransition() {
        return mMainFragment != null && mMainFragment.getView() != null;
    }

    @Override
    boolean isReadyForStartEntranceTransition() {
        return mMainFragment != null && mMainFragment.getView() != null
                && (!mIsPageRow || mMainFragmentAdapter.mFragmentHost.mDataReady);
    }

    void createHeadersTransition() {
        mHeadersTransition = TransitionHelper.loadTransition(getActivity(),
                mShowingHeaders
                        ? R.transition.lb_browse_headers_in : R.transition.lb_browse_headers_out);

        TransitionHelper.addTransitionListener(mHeadersTransition, new TransitionListener() {
            @Override
            public void onTransitionStart(Object transition) {
            }
            @Override
            public void onTransitionEnd(Object transition) {
                mHeadersTransition = null;
                if (mMainFragmentAdapter != null) {
                    mMainFragmentAdapter.onTransitionEnd();
                    if (!mShowingHeaders && mMainFragment != null) {
                        View mainFragmentView = mMainFragment.getView();
                        if (mainFragmentView != null && !mainFragmentView.hasFocus()) {
                            mainFragmentView.requestFocus();
                        }
                    }
                }
                if (mHeadersFragment != null) {
                    mHeadersFragment.onTransitionEnd();
                    if (mShowingHeaders) {
                        VerticalGridView headerGridView = mHeadersFragment.getVerticalGridView();
                        if (headerGridView != null && !headerGridView.hasFocus()) {
                            headerGridView.requestFocus();
                        }
                    }
                }

                // Animate TitleView once header animation is complete.
                updateTitleViewVisibility();

                if (mBrowseTransitionListener != null) {
                    mBrowseTransitionListener.onHeadersTransitionStop(mShowingHeaders);
                }
            }
        });
    }

    void updateTitleViewVisibility() {
        if (!mShowingHeaders) {
            boolean showTitleView;
            if (mIsPageRow && mMainFragmentAdapter != null) {
                // page fragment case:
                showTitleView = mMainFragmentAdapter.mFragmentHost.mShowTitleView;
            } else {
                // regular row view case:
                showTitleView = isFirstRowWithContent(mSelectedPosition);
            }
            if (showTitleView) {
                showTitle(TitleViewAdapter.FULL_VIEW_VISIBLE);
            } else {
                showTitle(false);
            }
        } else {
            // when HeaderFragment is showing,  showBranding and showSearch are slightly different
            boolean showBranding;
            boolean showSearch;
            if (mIsPageRow && mMainFragmentAdapter != null) {
                showBranding = mMainFragmentAdapter.mFragmentHost.mShowTitleView;
            } else {
                showBranding = isFirstRowWithContent(mSelectedPosition);
            }
            showSearch = isFirstRowWithContentOrPageRow(mSelectedPosition);
            int flags = 0;
            if (showBranding) flags |= TitleViewAdapter.BRANDING_VIEW_VISIBLE;
            if (showSearch) flags |= TitleViewAdapter.SEARCH_VIEW_VISIBLE;
            if (flags != 0) {
                showTitle(flags);
            } else {
                showTitle(false);
            }
        }
    }

    boolean isFirstRowWithContentOrPageRow(int rowPosition) {
        if (mAdapter == null || mAdapter.size() == 0) {
            return true;
        }
        for (int i = 0; i < mAdapter.size(); i++) {
            final Row row = (Row) mAdapter.get(i);
            if (row.isRenderedAsRowView() || row instanceof PageRow) {
                return rowPosition == i;
            }
        }
        return true;
    }

    boolean isFirstRowWithContent(int rowPosition) {
        if (mAdapter == null || mAdapter.size() == 0) {
            return true;
        }
        for (int i = 0; i < mAdapter.size(); i++) {
            final Row row = (Row) mAdapter.get(i);
            if (row.isRenderedAsRowView()) {
                return rowPosition == i;
            }
        }
        return true;
    }

    /**
     * Sets the {@link PresenterSelector} used to render the row headers.
     *
     * @param headerPresenterSelector The PresenterSelector that will determine
     *        the Presenter for each row header.
     */
    public void setHeaderPresenterSelector(PresenterSelector headerPresenterSelector) {
        mHeaderPresenterSelector = headerPresenterSelector;
        if (mHeadersFragment != null) {
            mHeadersFragment.setPresenterSelector(mHeaderPresenterSelector);
        }
    }

    private void setHeadersOnScreen(boolean onScreen) {
        ViewGroup.MarginLayoutParams lp;
        View containerList;
        containerList = mHeadersFragment.getView();
        lp = (ViewGroup.MarginLayoutParams) containerList.getLayoutParams();
        final int dimension = onScreen ? 0 : -mContainerListMarginStart;
        lp.setMarginStart(dimension);
        containerList.setLayoutParams(lp);
    }

    void showHeaders(boolean show) {
        if (DEBUG) Log.v(TAG, "showHeaders " + show);
        mHeadersFragment.setHeadersEnabled(show);
        setHeadersOnScreen(show);
        expandMainFragment(!show);
    }

    private void expandMainFragment(boolean expand) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) mScaleFrameLayout.getLayoutParams();
        params.setMarginStart(!expand ? mContainerListMarginStart : 0);
        mScaleFrameLayout.setLayoutParams(params);
        mMainFragmentAdapter.setExpand(expand);

        setMainFragmentAlignment();
        final float scaleFactor = !expand
                && mMainFragmentScaleEnabled
                && mMainFragmentAdapter.isScalingEnabled() ? mScaleFactor : 1;
        mScaleFrameLayout.setLayoutScaleY(scaleFactor);
        mScaleFrameLayout.setChildScale(scaleFactor);
    }

    private TvHeaderFragment.OnHeaderClickedListener mHeaderClickedListener =
            new TvHeaderFragment.OnHeaderClickedListener() {
                @Override
                public void onHeaderClicked(RowHeaderPresenter.ViewHolder viewHolder, Row row) {
                    if (!mCanShowHeaders || !mShowingHeaders || isInHeadersTransition()) {
                        return;
                    }
                    startHeadersTransitionInternal(false);
                    mMainFragment.getView().requestFocus();
                }
            };

    class MainFragmentItemViewSelectedListener implements OnItemViewSelectedListener {
        TvBrowseFragment.MainFragmentRowsAdapter mMainFragmentRowsAdapter;

        public MainFragmentItemViewSelectedListener(TvBrowseFragment.MainFragmentRowsAdapter fragmentRowsAdapter) {
            mMainFragmentRowsAdapter = fragmentRowsAdapter;
        }

        @Override
        public void onItemSelected(Presenter.ViewHolder itemViewHolder, Object item,
                                   RowPresenter.ViewHolder rowViewHolder, Row row) {
            int position = mMainFragmentRowsAdapter.getSelectedPosition();
            if (DEBUG) Log.v(TAG, "row selected position " + position);
            onRowSelected(position);
            if (mExternalOnItemViewSelectedListener != null) {
                mExternalOnItemViewSelectedListener.onItemSelected(itemViewHolder, item,
                        rowViewHolder, row);
            }
        }
    };

    private TvHeaderFragment.OnHeaderViewSelectedListener mHeaderViewSelectedListener =
            new TvHeaderFragment.OnHeaderViewSelectedListener() {
                @Override
                public void onHeaderSelected(RowHeaderPresenter.ViewHolder viewHolder, Row row) {
                    int position = mHeadersFragment.getSelectedPosition();
                    if (DEBUG) Log.v(TAG, "header selected position " + position);
                    onRowSelected(position);
                }
            };

    void onRowSelected(int position) {
        if (position != mSelectedPosition) {
            mSetSelectionRunnable.post(
                    position, TvBrowseFragment.SetSelectionRunnable.TYPE_INTERNAL_SYNC, true);
        }
    }

    void setSelection(int position, boolean smooth) {
        if (position == NO_POSITION) {
            return;
        }

        mSelectedPosition = position;
        if (mHeadersFragment == null || mMainFragmentAdapter == null) {
            // onDestroyView() called
            return;
        }
        mHeadersFragment.setSelectedPosition(position, smooth);
        replaceMainFragment(position);

        if (mMainFragmentRowsAdapter != null) {
            mMainFragmentRowsAdapter.setSelectedPosition(position, smooth);
        }

        updateTitleViewVisibility();
    }

    private void replaceMainFragment(int position) {
        if (createMainFragment(mAdapter, position)) {
            swapToMainFragment();
            expandMainFragment(!(mCanShowHeaders && mShowingHeaders));
            setupMainFragment();
            performPendingStates();
        }
    }

    private void swapToMainFragment() {
        final VerticalGridView gridView = mHeadersFragment.getVerticalGridView();
        if (isShowingHeaders() && gridView != null
                && gridView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            // if user is scrolling HeadersFragment,  swap to empty fragment and wait scrolling
            // finishes.
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.scale_frame, new Fragment()).commitAllowingStateLoss();
            gridView.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        gridView.removeOnScrollListener(this);
                        FragmentManager fm = getChildFragmentManager();
                        Fragment currentFragment = fm.findFragmentById(R.id.scale_frame);
                        if (currentFragment != mMainFragment) {
                            fm.beginTransaction().replace(R.id.scale_frame, mMainFragment).commitAllowingStateLoss();
                        }
                    }
                }
            });
        } else {
            // Otherwise swap immediately
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.scale_frame, mMainFragment).commitAllowingStateLoss();
        }
    }

    /**
     * Sets the selected row position with smooth animation.
     */
    public void setSelectedPosition(int position) {
        setSelectedPosition(position, true);
    }

    /**
     * Gets position of currently selected row.
     * @return Position of currently selected row.
     */
    public int getSelectedPosition() {
        return mSelectedPosition;
    }

    /**
     * @return selected row ViewHolder inside fragment created by {@link TvBrowseFragment.MainFragmentRowsAdapter}.
     */
    public RowPresenter.ViewHolder getSelectedRowViewHolder() {
        if (mMainFragmentRowsAdapter != null) {
            int rowPos = mMainFragmentRowsAdapter.getSelectedPosition();
            return mMainFragmentRowsAdapter.findRowViewHolderByPosition(rowPos);
        }
        return null;
    }

    /**
     * Sets the selected row position.
     */
    public void setSelectedPosition(int position, boolean smooth) {
        mSetSelectionRunnable.post(
                position, TvBrowseFragment.SetSelectionRunnable.TYPE_USER_REQUEST, smooth);
    }

    /**
     * Selects a Row and perform an optional task on the Row. For example
     * <code>setSelectedPosition(10, true, new ListRowPresenterSelectItemViewHolderTask(5))</code>
     * scrolls to 11th row and selects 6th item on that row.  The method will be ignored if
     * RowsFragment has not been created (i.e. before {@link #onCreateView(LayoutInflater,
     * ViewGroup, Bundle)}).
     *
     * @param rowPosition Which row to select.
     * @param smooth True to scroll to the row, false for no animation.
     * @param rowHolderTask Optional task to perform on the Row.  When the task is not null, headers
     * fragment will be collapsed.
     */
    public void setSelectedPosition(int rowPosition, boolean smooth,
                                    final Presenter.ViewHolderTask rowHolderTask) {
        if (mMainFragmentAdapterRegistry == null) {
            return;
        }
        if (rowHolderTask != null) {
            startHeadersTransition(false);
        }
        if (mMainFragmentRowsAdapter != null) {
            mMainFragmentRowsAdapter.setSelectedPosition(rowPosition, smooth, rowHolderTask);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        mHeadersFragment.setAlignment(mContainerListAlignTop);
        setMainFragmentAlignment();

        if (mCanShowHeaders && mShowingHeaders && mHeadersFragment != null
                && mHeadersFragment.getView() != null) {
            mHeadersFragment.getView().requestFocus();
        } else if ((!mCanShowHeaders || !mShowingHeaders) && mMainFragment != null
                && mMainFragment.getView() != null) {
            mMainFragment.getView().requestFocus();
        }

        if (mCanShowHeaders) {
            showHeaders(mShowingHeaders);
        }

        if (isEntranceTransitionEnabled()) {
            setEntranceTransitionStartState();
        }
    }

    private void onExpandTransitionStart(boolean expand, final Runnable callback) {
        if (expand) {
            callback.run();
            return;
        }
        // Run a "pre" layout when we go non-expand, in order to get the initial
        // positions of added rows.
        new TvBrowseFragment.ExpandPreLayout(callback, mMainFragmentAdapter, getView()).execute();
    }

    private void setMainFragmentAlignment() {
        int alignOffset = mContainerListAlignTop;
        if (mMainFragmentScaleEnabled
                && mMainFragmentAdapter.isScalingEnabled()
                && mShowingHeaders) {
            alignOffset = (int) (alignOffset / mScaleFactor + 0.5f);
        }
        mMainFragmentAdapter.setAlignment(alignOffset);
    }

    /**
     * Enables/disables headers transition on back key support. This is enabled by
     * default. The TvBrowseFragment will add a back stack entry when headers are
     * showing. Running a headers transition when the back key is pressed only
     * works when the headers state is {@link #HEADERS_ENABLED} or
     * {@link #HEADERS_HIDDEN}.
     * <p>
     * NOTE: If an Activity has its own onBackPressed() handling, you must
     * disable this feature. You may use {@link #startHeadersTransition(boolean)}
     * and {@link TvBrowseFragment.BrowseTransitionListener} in your own back stack handling.
     */
    public final void setHeadersTransitionOnBackEnabled(boolean headersBackStackEnabled) {
        mHeadersBackStackEnabled = headersBackStackEnabled;
    }

    /**
     * Returns true if headers transition on back key support is enabled.
     */
    public final boolean isHeadersTransitionOnBackEnabled() {
        return mHeadersBackStackEnabled;
    }

    private void readArguments(Bundle args) {
        if (args == null) {
            return;
        }
        if (args.containsKey(ARG_TITLE)) {
            setTitle(args.getString(ARG_TITLE));
        }
        if (args.containsKey(ARG_HEADERS_STATE)) {
            setHeadersState(args.getInt(ARG_HEADERS_STATE));
        }
    }

    /**
     * Sets the state for the headers column in the browse fragment. Must be one
     * of {@link #HEADERS_ENABLED}, {@link #HEADERS_HIDDEN}, or
     * {@link #HEADERS_DISABLED}.
     *
     * @param headersState The state of the headers for the browse fragment.
     */
    public void setHeadersState(int headersState) {
        if (headersState < HEADERS_ENABLED || headersState > HEADERS_DISABLED) {
            throw new IllegalArgumentException("Invalid headers state: " + headersState);
        }
        if (DEBUG) Log.v(TAG, "setHeadersState " + headersState);

        if (headersState != mHeadersState) {
            mHeadersState = headersState;
            switch (headersState) {
                case HEADERS_ENABLED:
                    mCanShowHeaders = true;
                    mShowingHeaders = true;
                    break;
                case HEADERS_HIDDEN:
                    mCanShowHeaders = true;
                    mShowingHeaders = false;
                    break;
                case HEADERS_DISABLED:
                    mCanShowHeaders = false;
                    mShowingHeaders = false;
                    break;
                default:
                    Log.w(TAG, "Unknown headers state: " + headersState);
                    break;
            }
            if (mHeadersFragment != null) {
                mHeadersFragment.setHeadersGone(!mCanShowHeaders);
            }
        }
    }

    /**
     * Returns the state of the headers column in the browse fragment.
     */
    public int getHeadersState() {
        return mHeadersState;
    }

    @Override
    protected Object createEntranceTransition() {
        return TransitionHelper.loadTransition(getActivity(),
                R.transition.lb_browse_entrance_transition);
    }

    @Override
    protected void runEntranceTransition(Object entranceTransition) {
        TransitionHelper.runTransition(mSceneAfterEntranceTransition, entranceTransition);
    }

    @Override
    protected void onEntranceTransitionPrepare() {
        mHeadersFragment.onTransitionPrepare();
        // setEntranceTransitionStartState() might be called when mMainFragment is null,
        // make sure it is called.
        mMainFragmentAdapter.setEntranceTransitionState(false);
        mMainFragmentAdapter.onTransitionPrepare();
    }

    @Override
    protected void onEntranceTransitionStart() {
        mHeadersFragment.onTransitionStart();
        mMainFragmentAdapter.onTransitionStart();
    }

    @Override
    protected void onEntranceTransitionEnd() {
        if (mMainFragmentAdapter != null) {
            mMainFragmentAdapter.onTransitionEnd();
        }

        if (mHeadersFragment != null) {
            mHeadersFragment.onTransitionEnd();
        }
    }

    void setSearchOrbViewOnScreen(boolean onScreen) {
        View searchOrbView = getTitleViewAdapter().getSearchAffordanceView();
        if (searchOrbView != null) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) searchOrbView.getLayoutParams();
            final int marginStart = onScreen ? 0 : -mContainerListMarginStart;
            lp.setMarginStart(marginStart);
            searchOrbView.setLayoutParams(lp);
        }
    }

    void setEntranceTransitionStartState() {
        setHeadersOnScreen(false);
        setSearchOrbViewOnScreen(false);
        mMainFragmentAdapter.setEntranceTransitionState(false);
    }

    void setEntranceTransitionEndState() {
        setHeadersOnScreen(mShowingHeaders);
        setSearchOrbViewOnScreen(true);
        mMainFragmentAdapter.setEntranceTransitionState(true);
    }

    private class ExpandPreLayout implements ViewTreeObserver.OnPreDrawListener {

        private final View mView;
        private final Runnable mCallback;
        private int mState;
        private TvBrowseFragment.MainFragmentAdapter mainFragmentAdapter;

        final static int STATE_INIT = 0;
        final static int STATE_FIRST_DRAW = 1;
        final static int STATE_SECOND_DRAW = 2;

        ExpandPreLayout(Runnable callback, TvBrowseFragment.MainFragmentAdapter adapter, View view) {
            mView = view;
            mCallback = callback;
            mainFragmentAdapter = adapter;
        }

        void execute() {
            mView.getViewTreeObserver().addOnPreDrawListener(this);
            mainFragmentAdapter.setExpand(false);
            // always trigger onPreDraw even adapter setExpand() does nothing.
            mView.invalidate();
            mState = STATE_INIT;
        }

        @Override
        public boolean onPreDraw() {
            if (getView() == null || getActivity() == null) {
                mView.getViewTreeObserver().removeOnPreDrawListener(this);
                return true;
            }
            if (mState == STATE_INIT) {
                mainFragmentAdapter.setExpand(true);
                // always trigger onPreDraw even adapter setExpand() does nothing.
                mView.invalidate();
                mState = STATE_FIRST_DRAW;
            } else if (mState == STATE_FIRST_DRAW) {
                mCallback.run();
                mView.getViewTreeObserver().removeOnPreDrawListener(this);
                mState = STATE_SECOND_DRAW;
            }
            return false;
        }
    }
}
