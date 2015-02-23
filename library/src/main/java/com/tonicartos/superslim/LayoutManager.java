package com.tonicartos.superslim;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.v4.util.SparseArrayCompat;
import android.support.v7.widget.LinearSmoothScroller;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

/**
 * A LayoutManager that lays out mSection headers with optional stickiness and uses a map of
 * sections to view layout managers to layout items.
 */
public class LayoutManager extends RecyclerView.LayoutManager {

    private static final int NO_POSITION_REQUEST = -1;

    private int mRequestPosition = NO_POSITION_REQUEST;

    private Rect mRect = new Rect();

    private int mRequestPositionOffset = 0;

    private boolean mDisableStickyHeaderDisplay = false;

    private SparseArrayCompat<SectionLayoutManager> mSectionLayouts = new SparseArrayCompat<>();

    private boolean mSmoothScrollEnabled = true;

    /**
     * Find the position of the first completely visible item.
     *
     * @return Position of first completely visible item.
     */
    public int findFirstCompletelyVisibleItemPosition() {
        final LayoutParams lp = (LayoutParams) getChildAt(0).getLayoutParams();
        final SectionLayoutManager manager = getSectionLayoutManager(lp.sectionManager);

        return manager.findFirstCompletelyVisibleItemPosition(lp.getTestedFirstPosition());
    }

    /**
     * Find the position of the first visible item.
     *
     * @return Position of first visible item.
     */
    public int findFirstVisibleItemPosition() {
        final LayoutParams lp = (LayoutParams) getChildAt(0).getLayoutParams();
        final SectionLayoutManager manager = getSectionLayoutManager(lp.sectionManager);

        return manager.findFirstVisibleItemPosition(lp.getTestedFirstPosition());
    }

    /**
     * Find the position of the last completely visible item.
     *
     * @return Position of last completely visible item.
     */
    public int findLastCompletelyVisibleItemPosition() {
        final LayoutParams lp = (LayoutParams) getChildAt(getChildCount() - 1).getLayoutParams();
        final SectionLayoutManager manager = getSectionLayoutManager(lp.sectionManager);

        return manager.findLastCompletelyVisibleItemPosition(lp.getTestedFirstPosition());
    }

    /**
     * Find the position of the last visible item.
     *
     * @return Position of last visible item.
     */
    public int findLastVisibleItemPosition() {
        final LayoutParams lp = (LayoutParams) getChildAt(getChildCount() - 1).getLayoutParams();
        final SectionLayoutManager manager = getSectionLayoutManager(lp.sectionManager);

        return manager.findLastVisibleItemPosition(lp.getTestedFirstPosition());
    }

    public boolean isSmoothScrollEnabled() {
        return mSmoothScrollEnabled;
    }

    public void setSmoothScrollEnabled(boolean smoothScrollEnabled) {
        mSmoothScrollEnabled = smoothScrollEnabled;
    }

    public FillResult layoutAndAddHeader(LayoutState state, SectionData section,
            FillResult fillResult) {
        final LayoutState.View header = section.getSectionHeader(state);
        if (header == null) {
            return fillResult;
        }

        final LayoutParams params = header.getLayoutParams();
        final int width = getDecoratedMeasuredWidth(header.view);
        final int height = getDecoratedMeasuredHeight(header.view);

        // Adjust marker line if needed.
        if (params.isHeaderInline() && !params.isHeaderOverlay()) {
            fillResult.markerStart -= height;
        }

        // Check header if header is stuck.
        final boolean isStuck = params.isHeaderSticky() && fillResult.markerStart < 0
                && !mDisableStickyHeaderDisplay;

        // Attach after section children if overlay, otherwise before.
        final int attachIndex;
        if (isStuck || params.isHeaderOverlay()) {
            attachIndex = fillResult.firstChildIndex + fillResult.addedChildCount;
        } else {
            attachIndex = fillResult.firstChildIndex;
        }

        // Attach header.
        if (header.wasCached) {
            if ((params.isHeaderSticky() && !mDisableStickyHeaderDisplay)
                    || getDecoratedBottom(header.view) >= 0) {
                attachView(header.view, attachIndex);
                state.decacheView(section.getFirstPosition());
                fillResult.positionStart -= 1;
            }
            if (!params.isHeaderSticky() || mDisableStickyHeaderDisplay) {
                // Layout unneeded if the header is not sticky and was cached.
                return fillResult;
            }
        }

        // Do Layout

        Rect rect = setHeaderRectSides(state, section, width, params, mRect);
        rect = setHeaderRectTopAndBottom(state, fillResult, height, params, rect);
        if (rect.bottom < 0) {
            // Header is offscreen.
            return fillResult;
        } else if (!header.wasCached) {
            fillResult.positionStart -= 1;
            addView(header.view, attachIndex);
        }

        layoutDecorated(header.view, rect.left, rect.top, rect.right, rect.bottom);

        return fillResult;
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        int itemCount = state.getItemCount();
        if (itemCount == 0) {
            detachAndScrapAttachedViews(recycler);
            return;
        }

        final int requestedPosition;
        final int borderLine;

        if (mRequestPosition != NO_POSITION_REQUEST) {
            requestedPosition = mRequestPosition;
            mRequestPosition = NO_POSITION_REQUEST;
            borderLine = mRequestPositionOffset;
            mRequestPositionOffset = 0;
        } else {
            View anchorView = getAnchorChild(itemCount);
            requestedPosition = anchorView == null ? 0 : getPosition(anchorView);
            borderLine = getBorderLine(anchorView, Direction.END);
        }

        detachAndScrapAttachedViews(recycler);

        final int anchorPosition = determineAnchorPosition(
                new LayoutState(this, recycler, state), requestedPosition);

        fill(recycler, state, anchorPosition, borderLine, true);
    }

    @Override
    public RecyclerView.LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    public LayoutParams generateLayoutParams(ViewGroup.LayoutParams lp) {
        final LayoutParams newLp = new LayoutParams(lp);
        newLp.width = LayoutParams.MATCH_PARENT;
        newLp.height = LayoutParams.MATCH_PARENT;
        newLp.init(lp);
        return newLp;
    }

    @Override
    public RecyclerView.LayoutParams generateLayoutParams(Context c, AttributeSet attrs) {
        return new LayoutParams(c, attrs);
    }

    @Override
    public int scrollVerticallyBy(int dy, RecyclerView.Recycler recycler,
            RecyclerView.State state) {
        Log.d("scroll", "##### scroll #####");
        int numChildren = getChildCount();
        if (numChildren == 0) {
            return 0;
        }

        LayoutState layoutState = new LayoutState(this, recycler, state);

        final Direction direction = dy > 0 ? Direction.END : Direction.START;

        final int leadingEdge = direction == Direction.END ? getHeight() + dy : dy;
        final int fillEdge = fillUntil(leadingEdge, direction, layoutState);
        final int delta;
        if (direction == Direction.END) {
            // padding added here to allow extra scroll
            int fillDelta = fillEdge - getHeight() + getPaddingBottom();
            delta = fillDelta < dy ? fillDelta : dy;
        } else {
            int fillDelta = fillEdge - getPaddingTop();
            delta = fillDelta > dy ? fillDelta : dy;
        }

        if (delta != 0) {
            offsetChildrenVertical(-delta);

            trimTail(direction, layoutState);
        }

        layoutState.recycleCache();

        return delta;
    }

    /**
     * Trim all content wholly beyond the direction edge. If the direction is START, then update the
     * header of the section intersecting the top edge.
     *
     * @param direction   Direction of edge to trim against.
     * @param layoutState Layout state.
     */
    private void trimTail(Direction direction, LayoutState layoutState) {
        // TODO:
    }

    /**
     * Fill up to a line in a given direction.
     *
     * @param leadingEdge Line to fill up to. Content will not be wholly beyond this line.
     * @param direction   Direction fill will be taken towards.
     * @param layoutState Layout state.
     * @return Line to which content has been filled. If the line is before the leading edge then
     * the end of the data set has been reached.
     */
    private int fillUntil(int leadingEdge, Direction direction, LayoutState layoutState) {
        if (direction == Direction.START) {
            return fillToStart(leadingEdge, layoutState);
        } else {
            return fillToEnd(leadingEdge, layoutState);
        }
    }

    /**
     * Fill the space between the last content item and the leadingEdge.
     *
     * @param leadingEdge Line to fill up to. Content will not be wholly beyond this line.
     * @param state       Layout state.
     * @return Line to which content has been filled. If the line is before the leading edge then
     * the end of the data set has been reached.
     */
    private int fillToEnd(int leadingEdge, LayoutState state) {
        final View anchor = getAnchorAtEnd();
        final int sfp = ((LayoutParams) anchor.getLayoutParams()).getTestedFirstPosition();
        final View first = getHeaderOrFirstViewForSection(sfp, Direction.END, state);
        final SectionData2 sd = new SectionData2(this, first);

        final SectionLayoutManager slm = getSectionLayoutManager(sd.sectionManager);
        int markerLine = slm.init(sd).finishFillToEnd(leadingEdge, anchor, sd, state);
        Log.d("finishFillToEnd", "markerLine " + markerLine);

        markerLine = updateHeaderForEnd(markerLine, sd);
        Log.d("updateHeaderForEnd", "markerLine " + markerLine);

        markerLine = fillNextSectionToEnd(leadingEdge, markerLine, state);

        return markerLine;
    }

    /**
     * Fill out the next section as far as possible. The marker line is used as a start line to
     * position content from. If necessary, room for headers is given before laying out the section
     * content. However, headers are always added to an index after the section content.
     *
     * @param leadingEdge Line to fill up to. Content will not be wholly beyond this line.
     * @param markerLine  Start line to begin placing content at.
     * @param state       Layout state.
     * @return Line to which content has been filled.
     */
    private int fillNextSectionToEnd(int leadingEdge, int markerLine, LayoutState state) {
        if (markerLine >= leadingEdge) {
            return markerLine;
        }

        View last = getAnchorAtEnd();
        int anchorPosition = getPosition(last) + 1;

        if (anchorPosition >= state.recyclerState.getItemCount()) {
            return markerLine;
        }

        LayoutState.View header = state.getView(anchorPosition);
        SectionData2 sd = new SectionData2(this, header.view);
        if (sd.hasHeader) {
            Log.d("fillNextSectionToEnd", "found header");
            measureHeader(header);
            sd = new SectionData2(this, header.view);
            markerLine = layoutHeaderTowardsEnd(header.view, markerLine, sd, state);
            Log.d("fillNextSectionToEnd", "markerLine " + markerLine);
            anchorPosition += 1;
        } else {
            state.cacheView(anchorPosition, header.view);
        }

        if (anchorPosition < state.recyclerState.getItemCount()) {
            Log.d("fillNextSectionToEnd", "fill out section");
            SectionLayoutManager slm = getSectionLayoutManager(sd.sectionManager);
            markerLine = slm.init(sd).fillToEnd(leadingEdge, markerLine, anchorPosition, sd, state);
            Log.d("fillNextSectionToEnd", "markerLine " + markerLine);
        }

        if (sd.hasHeader) {
            Log.d("fillNextSectionToEnd", "attaching header");
            addView(header.view);
            if (header.wasCached) {
                state.decacheView(sd.firstPosition);
            }
            markerLine = Math.max(getDecoratedBottom(header.view), markerLine);
            Log.d("fillNextSectionToEnd", "markerLine " + markerLine);
        }

        return fillNextSectionToEnd(leadingEdge, markerLine, state);
    }

    /**
     * Layout header for fill to end.
     *
     * @param header     Header to be laid out.
     * @param markerLine Start of section.
     * @param sd         Section data.
     * @param state      Layout state.
     * @return Line at which to start filling out the section's content.
     */
    private int layoutHeaderTowardsEnd(View header, int markerLine, SectionData2 sd,
            LayoutState state) {
        Rect r = setHeaderRectSides(mRect, sd, state);

        r.top = markerLine;
        r.bottom = r.top + sd.headerHeight;

        if (sd.headerParams.isHeaderInline() && !sd.headerParams.isHeaderOverlay()) {
            markerLine = r.bottom;
        }

        layoutDecorated(header, r.left, r.top, r.right, r.bottom);

        return markerLine;
    }

    /**
     * Find the header for this section, if any, and move it to be attached after the section's
     * content items. Updates the line showing the end of the section.
     *
     * @param markerLine End of the section as given by the SLM.
     * @param sd         Section data.
     * @return The end of the section including the header.
     */
    private int updateHeaderForEnd(int markerLine, SectionData2 sd) {
        View header = null;
        LayoutParams params = null;
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View child = getChildAt(i);
            params = (LayoutParams) child.getLayoutParams();
            if (params.getTestedFirstPosition() != sd.firstPosition) {
                break;
            } else if (params.isHeader) {
                header = child;
                break;
            }
        }

        if (header == null) {
            return markerLine;
        }

        // Just keep headers at the end.
        detachView(header);
        attachView(header, -1);

        return Math.max(markerLine, getDecoratedBottom(header));
    }

    private int computeMarkerLine(Direction direction, SectionLayoutManager slm, int sectionId) {
        if (direction == Direction.END) {
            return slm.getLowestEdge(sectionId, getHeight());
        } else {
            return slm.getHighestEdge(sectionId, 0);
        }
    }

    /**
     * Find an anchor to fill to end from.
     *
     * @return Non-header view closest to the end edge.
     */
    private View getAnchorAtEnd() {
        if (getChildCount() == 1) {
            return getChildAt(0);
        }
        View candidate = getChildAt(getChildCount() - 1);
        LayoutParams candidateParams = (LayoutParams) candidate.getLayoutParams();
        if (candidateParams.isHeader) {
            // Try one above.
            View check = getChildAt(getChildCount() - 2);
            LayoutParams checkParams = (LayoutParams) check.getLayoutParams();
            if (checkParams.getTestedFirstPosition() == candidateParams.getTestedFirstPosition()) {
                candidate = check;
            }
        }
        return candidate;
    }

    private View getAnchorAtStart() {
        // TODO:
        return getChildAt(0);
    }

    private int fillToStart(int leadingEdge, LayoutState layoutState) {
        // TODO:
        return leadingEdge;
    }

    @Override
    public boolean canScrollVertically() {
        return true;
    }

    @Override
    public void scrollToPosition(int position) {
        if (position < 0 || getItemCount() <= position) {
            Log.e("SuperSLiM.LayoutManager", "Ignored scroll to " + position +
                    " as it is not within the item range 0 - " + getItemCount());
            return;
        }

        mRequestPosition = position;
        requestLayout();
    }

    @Override
    public void smoothScrollToPosition(final RecyclerView recyclerView, RecyclerView.State state,
            final int position) {
        if (position < 0 || getItemCount() <= position) {
            Log.e("SuperSLiM.LayoutManager", "Ignored smooth scroll to " + position +
                    " as it is not within the item range 0 - " + getItemCount());
            return;
        }

        // Temporarily disable sticky headers.
        mDisableStickyHeaderDisplay = true;
        requestLayout();

        recyclerView.getHandler().post(new Runnable() {
            @Override
            public void run() {
                LinearSmoothScroller smoothScroller = new LinearSmoothScroller(
                        recyclerView.getContext()) {
                    @Override
                    protected void onChildAttachedToWindow(View child) {
                        super.onChildAttachedToWindow(child);
                    }

                    @Override
                    protected void onStop() {
                        super.onStop();
                        // Turn sticky headers back on.
                        mDisableStickyHeaderDisplay = false;
                    }

                    @Override
                    protected int getVerticalSnapPreference() {
                        return LinearSmoothScroller.SNAP_TO_START;
                    }

                    @Override
                    public int calculateDyToMakeVisible(View view, int snapPreference) {
                        final RecyclerView.LayoutManager layoutManager = getLayoutManager();
                        if (!layoutManager.canScrollVertically()) {
                            return 0;
                        }
                        final RecyclerView.LayoutParams params = (RecyclerView.LayoutParams)
                                view.getLayoutParams();
                        final int top = layoutManager.getDecoratedTop(view) - params.topMargin;
                        final int bottom = layoutManager.getDecoratedBottom(view)
                                + params.bottomMargin;
                        final int start = getPosition(view) == 0 ? layoutManager.getPaddingTop()
                                : 0;
                        final int end = layoutManager.getHeight() - layoutManager
                                .getPaddingBottom();
                        int dy = calculateDtToFit(top, bottom, start, end, snapPreference);
                        return dy == 0 ? 1 : dy;
                    }

                    @Override
                    public PointF computeScrollVectorForPosition(int targetPosition) {
                        if (getChildCount() == 0) {
                            return null;
                        }

                        return new PointF(0, getDirectionToPosition(targetPosition));
                    }
                };
                smoothScroller.setTargetPosition(position);
                startSmoothScroll(smoothScroller);
            }
        });
    }

    @Override
    public void onAdapterChanged(RecyclerView.Adapter oldAdapter, RecyclerView.Adapter newAdapter) {
        removeAllViews();
    }

    @Override
    public void onItemsUpdated(RecyclerView recyclerView, int positionStart, int itemCount) {
        super.onItemsUpdated(recyclerView, positionStart, itemCount);

        View first = getChildAt(0);
        View last = getChildAt(getChildCount() - 1);
        if (positionStart + itemCount <= getPosition(first)) {
            return;
        }

        if (positionStart <= getPosition(last)) {
            requestLayout();
        }
    }

    @Override
    public int computeVerticalScrollExtent(RecyclerView.State state) {
        if (state.getItemCount() == 0) {
            return 0;
        }

        if (!mSmoothScrollEnabled) {
            return getChildCount();
        }

        float contentInView = getChildCount();

        // Work out fraction of content lost off top and bottom.
        contentInView -= getFractionOfContentAbove(state, true);
        contentInView -= getFractionOfContentBelow(state, true);

        return (int) (contentInView / state.getItemCount() * getHeight());
    }

    @Override
    public int computeVerticalScrollOffset(RecyclerView.State state) {
        if (state.getItemCount() == 0) {
            return 0;
        }

        final View child = getChildAt(0);
        if (!mSmoothScrollEnabled) {
            return getPosition(child);
        }

        float contentAbove = getPosition(child);
        contentAbove += getFractionOfContentAbove(state, false);
        return (int) (contentAbove / state.getItemCount() * getHeight());
    }

    @Override
    public int computeVerticalScrollRange(RecyclerView.State state) {
        if (!mSmoothScrollEnabled) {
            return state.getItemCount();
        }

        return getHeight();
    }

    @Override
    public Parcelable onSaveInstanceState() {
        SavedState state = new SavedState();
        View view = getAnchorChild(getItemCount());
        if (view == null) {
            state.anchorPosition = 0;
            state.anchorOffset = 0;
        } else {
            state.anchorPosition = getPosition(view);
            state.anchorOffset = getDecoratedTop(view);
        }
        return state;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        mRequestPosition = ((SavedState) state).anchorPosition;
        mRequestPositionOffset = ((SavedState) state).anchorOffset;
        requestLayout();
    }

    /**
     * Register a SectionLayoutManager.
     *
     * @param id      Id of layout. Referenced by first section view.
     * @param manager SectionLayoutManager to register.
     */
    public void registerSectionLayoutManager(int id, SectionLayoutManager manager) {
        mSectionLayouts.put(id, manager);
    }

    void measureHeader(LayoutState.View header) {
        // Width to leave for the mSection to which this header belongs. Only applies if the
        // header is being laid out adjacent to the mSection.
        int unavailableWidth = 0;
        LayoutParams lp = (LayoutParams) header.view.getLayoutParams();
        int recyclerWidth = getWidth() - getPaddingStart() - getPaddingEnd();
        if (!lp.isHeaderOverlay()) {
            if (lp.isHeaderStartAligned() && !lp.headerStartMarginIsAuto) {
                unavailableWidth = recyclerWidth - lp.headerMarginStart;
            } else if (lp.isHeaderEndAligned() && !lp.headerEndMarginIsAuto) {
                unavailableWidth = recyclerWidth - lp.headerMarginEnd;
            }
        }
        measureChildWithMargins(header.view, unavailableWidth, 0);
    }

    private int determineAnchorPosition(LayoutState state, int position) {
        SectionData section = new SectionData(this, state, Direction.NONE, position, 0);

        if (section.getFirstPosition() == position && section.getSectionHeader(state) != null &&
                section.getSectionHeader(state).getLayoutParams().isHeaderInline()) {
            // Already know what to do in this case.
            return position;
        }

        return mSectionLayouts.get(section.getLayoutId())
                .getAnchorPosition(state, section, position);
    }

    private void fill(RecyclerView.Recycler recycler, RecyclerView.State rvs,
            final int anchorPosition, int scrappedBorderLine, boolean scrapped) {

        LayoutState state = new LayoutState(this, recycler, rvs);
        final int itemCount = state.recyclerState.getItemCount();
        final int recyclerViewHeight = getHeight();

        if (anchorPosition >= itemCount || anchorPosition < 0) {
            return;
        }

        state.detachAndCacheAllViews();

        // Borderline
        int borderline = scrapped ? scrappedBorderLine
                : getBorderLine(state, anchorPosition, Direction.END);

        // Prepare anchor section.
        SectionData section = new SectionData(this, state, Direction.NONE, anchorPosition,
                borderline);
        SectionLayoutManager sectionManager = getSectionLayoutManager(section.getLayoutId());
        section.loadMargins(this, sectionManager);

        // Fill anchor section.
        FillResult anchorResult = sectionManager.fill(state, section);
        anchorResult = layoutAndAddHeader(state, section, anchorResult);

        // Fill sections before anchor to start.
        FillResult fillResult;
        fillResult = fillSections(state, anchorResult, recyclerViewHeight, Direction.START);
        final int finalStartMarker = fillResult.markerStart;
        final int finalStartPosition = fillResult.positionStart;

        // Fill sections after anchor to end.
        fillResult = fillSections(state, anchorResult, recyclerViewHeight, Direction.END);
        final int finalEndMarker = fillResult.markerEnd;
        final int finalEndPosition = fillResult.positionEnd;

        state.recycleCache();
    }

    private FillResult fillSections(LayoutState layoutState, FillResult fillState,
            int recyclerViewHeight, Direction direction) {
        while (true) {
            final int anchor;
            final SectionData section;
            if (direction == Direction.END) {
                anchor = fillState.positionEnd + 1;
                if (fillState.markerEnd >= recyclerViewHeight
                        || anchor >= layoutState.recyclerState.getItemCount()) {
                    break;
                }
                section = new SectionData(this, layoutState, direction, anchor,
                        fillState.markerEnd);
            } else {
                anchor = fillState.positionStart - 1;
                if (fillState.markerStart <= 0 || anchor < 0) {
                    break;
                }
                section = new SectionData(this, layoutState, direction, anchor,
                        fillState.markerStart);
            }

            SectionLayoutManager sectionManager = getSectionLayoutManager(section.getLayoutId());
            section.loadMargins(this, sectionManager);
            fillState = sectionManager.fill(layoutState, section);
            fillState = layoutAndAddHeader(layoutState, section, fillState);
        }
        return fillState;
    }

    /**
     * Find header or, if it cannot be found, the first view for a section.
     *
     * @param sfp Section to look for header inside of. Search is expected to start
     *                             inside the section so it must be at the matching end specified by
     *                             the direction.
     * @param direction            Direction to look in. Direction.END means to look from the start
     *                             to the end.
     * @return Null if no header or first item found, otherwise the found view.
     */
    private View findAttachedHeaderOrFirstViewForSection(final int sfp, final Direction direction) {
        int childIndex = direction == Direction.START ? 0 : getChildCount() - 1;
        int step = direction == Direction.START ? 1 : -1;
        for (; 0 <= childIndex && childIndex < getChildCount(); childIndex += step) {
            View child = getChildAt(childIndex);

            if (getPosition(child) == sfp) {
                return child;
            }
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            if (params.getTestedFirstPosition() != sfp) {
                break;
            }
        }

        return null;
    }

    private View getHeaderOrFirstViewForSection(int sfp, Direction direction, LayoutState state) {
        View view = findAttachedHeaderOrFirstViewForSection(sfp, direction);
        if (view == null) {
            LayoutState.View stateView = state.getView(sfp);
            view = stateView.view;
            if (stateView.getLayoutParams().isHeader) {
                measureHeader(stateView);
            }
            state.cacheView(sfp, view);
        }
        return view;
    }

    /**
     * Find a view that is the header for the specified section. Looks in direction specified from
     * opposite end.
     *
     * @param itemCount            Current number of items in adapter.
     * @param sectionFirstPosition Section to look for header inside of. Search is expected to start
     *                             inside the section so it must be at the matching end specified by
     *                             the direction.
     * @param direction            Direction to look in. Direction.END means to look from the start
     *                             to the end.
     * @return Null if no header found, otherwise the header view.
     */
    private View findAttachedHeaderForSection(final int itemCount, final int sectionFirstPosition,
            final Direction direction) {
        int position = direction == Direction.END ? 0 : getChildCount() - 1;
        int nextStep = direction == Direction.END ? 1 : -1;
        for (; 0 <= position && position < itemCount; position += nextStep) {
            View child = getChildAt(position);
            if (child == null) {
                continue;
            }
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            if (params.getTestedFirstPosition() != sectionFirstPosition) {
                break;
            } else if (params.isHeader) {
                return child;
            }
        }
        return null;
    }

    /**
     * Find the first view in the hierarchy that can act as an anchor.
     *
     * @param itemCount Number of items currently in the adapter.
     * @return The anchor view, or null if no view is a valid anchor.
     */
    private View getAnchorChild(final int itemCount) {
        if (getChildCount() > 0) {
            final int childCount = getChildCount();

            for (int i = 0; i < childCount; i++) {
                final View view = getChildAt(i);

                // Skip headers.
                if (((LayoutParams) view.getLayoutParams()).isHeader) {
                    //TODO: Handle empty sections with headers.
                    continue;
                }

                final int position = getPosition(view);
                if (position >= 0 && position < itemCount) {
                    return view;
                }
            }
        }
        return null;
    }

    /**
     * Work out the borderline from the given anchor view and the intended direction to fill the
     * view hierarchy.
     *
     * @param anchorView Anchor view to determine borderline from.
     * @param direction  Direction fill will be taken towards.
     * @return Borderline.
     */
    private int getBorderLine(View anchorView, Direction direction) {
        int borderline;
        if (anchorView == null) {
            if (direction == Direction.START) {
                borderline = getPaddingBottom();
            } else {
                borderline = getPaddingTop();
            }
        } else if (direction == Direction.START) {
            borderline = getDecoratedBottom(anchorView);
        } else {
            borderline = getDecoratedTop(anchorView);
        }
        return borderline;
    }

    private int getBorderLine(LayoutState state, int anchorPosition,
            Direction direction) {
        int borderline;
        final android.view.View marker = state.getCachedView(anchorPosition);
        if (marker == null) {
            if (direction == Direction.START) {
                borderline = getPaddingBottom();
            } else {
                borderline = getPaddingTop();
            }
        } else if (direction == Direction.START) {
            borderline = getDecoratedBottom(marker);
        } else {
            borderline = getDecoratedTop(marker);
        }
        return borderline;
    }

    private int getDirectionToPosition(int targetPosition) {
        LayoutParams lp = (LayoutParams) getChildAt(0).getLayoutParams();
        final View startSectionFirstView = getSectionLayoutManager(lp.sectionManager)
                .getFirstVisibleView(lp.getTestedFirstPosition(), true);
        return targetPosition < getPosition(startSectionFirstView) ? -1 : 1;
    }

    private float getFractionOfContentAbove(RecyclerView.State state, boolean ignorePosition) {
        float fractionOffscreen = 0;

        View child = getChildAt(0);

        final int anchorPosition = getPosition(child);
        int numBeforeAnchor = 0;

        float top = getDecoratedTop(child);
        float bottom = getDecoratedBottom(child);
        if (bottom < 0) {
            fractionOffscreen = 1;
        } else if (0 <= top) {
            fractionOffscreen = 0;
        } else {
            float height = getDecoratedMeasuredHeight(child);
            fractionOffscreen = -top / height;
        }
        LayoutParams params = (LayoutParams) child.getLayoutParams();
        if (params.isHeader && params.isHeaderInline()) {
            // Header must not be stickied as it is not attached after section items.
            return fractionOffscreen;
        }

        // Run through all views in the section and add up values offscreen.
        int firstPosition = -1;
        SparseArray<Boolean> positionsOffscreen = new SparseArray<>();
        for (int i = 1; i < getChildCount(); i++) {
            child = getChildAt(i);
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (lp.sectionManager != params.sectionManager) {
                break;
            }

            final int position = getPosition(child);
            if (!ignorePosition && position < anchorPosition) {
                numBeforeAnchor += 1;
            }

            top = getDecoratedTop(child);
            bottom = getDecoratedBottom(child);
            if (bottom < 0) {
                fractionOffscreen += 1;
            } else if (0 <= top) {
                continue;
            } else {
                float height = getDecoratedMeasuredHeight(child);
                fractionOffscreen += -top / height;
            }

            if (!lp.isHeader) {
                if (firstPosition == -1) {
                    firstPosition = position;
                }
                positionsOffscreen.put(position, true);
            }
        }

        return fractionOffscreen - numBeforeAnchor - getSectionLayoutManager(params.sectionManager)
                .howManyMissingAbove(firstPosition, positionsOffscreen);
    }

    private float getFractionOfContentBelow(RecyclerView.State state, boolean ignorePosition) {
        final float parentHeight = getHeight();
        View child = getChildAt(getChildCount() - 1);

        final int anchorPosition = getPosition(child);
        int countAfter = 0;

        LayoutParams params = (LayoutParams) child.getLayoutParams();

        float fractionOffscreen = 0;
        int lastPosition = -1;
        SparseArray<Boolean> positionsOffscreen = new SparseArray<>();
        // Run through all views in the section and add up values offscreen.
        for (int i = 1; i <= getChildCount(); i++) {
            child = getChildAt(getChildCount() - i);
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (lp.sectionManager != params.sectionManager) {
                break;
            }

            int position = getPosition(child);
            if (!lp.isHeader && !ignorePosition && position > anchorPosition) {
                countAfter += 1;
            }

            float bottom = getDecoratedBottom(child);
            float top = getDecoratedTop(child);
            if (bottom <= parentHeight) {
                continue;
            } else if (parentHeight < top) {
                fractionOffscreen += 1;
            } else {
                float height = getDecoratedMeasuredHeight(child);
                fractionOffscreen += (bottom - parentHeight) / height;
            }

            if (!lp.isHeader) {
                if (lastPosition == -1) {
                    lastPosition = position;
                }
                positionsOffscreen.put(position, true);
            }
        }

        return fractionOffscreen - countAfter - getSectionLayoutManager(params.sectionManager)
                .howManyMissingBelow(lastPosition, positionsOffscreen);
    }

    private SectionLayoutManager getSectionLayoutManager(int sectionManager) {
        SectionLayoutManager manager = mSectionLayouts.get(sectionManager);
        if (manager == null) {
            throw new UnknownSectionLayoutException(sectionManager);
        }
        return manager;
    }

    private Rect setHeaderRectSides(Rect r, SectionData2 sd, LayoutState state) {
        if (sd.headerParams.isHeaderEndAligned()) {
            // Position header from end edge.
            if (!sd.headerParams.isHeaderOverlay() && !sd.headerParams.headerEndMarginIsAuto
                    && sd.marginEnd > 0) {
                // Position inside end margin.
                if (state.isLTR) {
                    r.left = getWidth() - sd.marginEnd - getPaddingEnd();
                    r.right = r.left + sd.headerWidth;
                } else {
                    r.right = sd.marginEnd + getPaddingEnd();
                    r.left = r.right - sd.headerWidth;
                }
            } else if (state.isLTR) {
                r.right = getWidth() - getPaddingEnd();
                r.left = r.right - sd.headerWidth;
            } else {
                r.left = getPaddingEnd();
                r.right = r.left + sd.headerWidth;
            }
        } else if (sd.headerParams.isHeaderStartAligned()) {
            // Position header from start edge.
            if (!sd.headerParams.isHeaderOverlay() && !sd.headerParams.headerStartMarginIsAuto
                    && sd.marginStart > 0) {
                // Position inside start margin.
                if (state.isLTR) {
                    r.right = sd.marginStart + getPaddingStart();
                    r.left = r.right - sd.headerWidth;
                } else {
                    r.left = getWidth() - sd.marginStart - getPaddingStart();
                    r.right = r.left + sd.headerWidth;
                }
            } else if (state.isLTR) {
                r.left = getPaddingStart();
                r.right = r.left + sd.headerWidth;
            } else {
                r.right = getWidth() - getPaddingStart();
                r.left = r.right - sd.headerWidth;
            }
        } else {
            // Header is not aligned to a directed edge and assumed to fill the width available.
            r.left = getPaddingLeft();
            r.right = r.left + sd.headerWidth;
        }

        return r;
    }

    private Rect setHeaderRectSides(LayoutState state, SectionData section, int width,
            LayoutParams params, Rect r) {

        if (params.isHeaderEndAligned()) {
            // Position header from end edge.
            if (!params.isHeaderOverlay() && !params.headerEndMarginIsAuto
                    && section.getHeaderMarginEnd() > 0) {
                // Position inside end margin.
                if (state.isLTR) {
                    r.left = getWidth() - section.getHeaderMarginEnd() - getPaddingEnd();
                    r.right = r.left + width;
                } else {
                    r.right = section.getHeaderMarginEnd() + getPaddingEnd();
                    r.left = r.right - width;
                }
            } else if (state.isLTR) {
                r.right = getWidth() - getPaddingEnd();
                r.left = r.right - width;
            } else {
                r.left = getPaddingEnd();
                r.right = r.left + width;
            }
        } else if (params.isHeaderStartAligned()) {
            // Position header from start edge.
            if (!params.isHeaderOverlay() && !params.headerStartMarginIsAuto
                    && section.getHeaderMarginStart() > 0) {
                // Position inside start margin.
                if (state.isLTR) {
                    r.right = section.getHeaderMarginStart() + getPaddingStart();
                    r.left = r.right - width;
                } else {
                    r.left = getWidth() - section.getHeaderMarginStart() - getPaddingStart();
                    r.right = r.left + width;
                }
            } else if (state.isLTR) {
                r.left = getPaddingStart();
                r.right = r.left + width;
            } else {
                r.right = getWidth() - getPaddingStart();
                r.left = r.right - width;
            }
        } else {
            // Header is not aligned to a directed edge and assumed to fill the width available.
            r.left = getPaddingLeft();
            r.right = r.left + width;
        }

        return r;
    }

    private Rect setHeaderRectTopAndBottom(LayoutState state, FillResult fillResult, int height,
            LayoutParams params, Rect r) {
        r.top = fillResult.markerStart;
        if (params.headerDisplay != LayoutParams.HEADER_INLINE && fillResult.headerOffset < 0) {
            r.top += fillResult.headerOffset;
        }
        r.bottom = r.top + height;

        if (params.isHeaderSticky() && !mDisableStickyHeaderDisplay) {
            if (r.top < 0) {
                r.top = 0;
                r.bottom = height;
            }
            if (r.bottom > fillResult.markerEnd) {
                r.bottom = fillResult.markerEnd;
                r.top = r.bottom - height;
            }
        }

        return r;
    }

    public enum Direction {
        START,
        END,
        NONE
    }

    public static class LayoutParams extends RecyclerView.LayoutParams {

        public static final int HEADER_INLINE = 0x01;

        public static final int HEADER_ALIGN_START = 0x02;

        public static final int HEADER_ALIGN_END = 0x04;

        public static final int HEADER_OVERLAY = 0x08;

        public static final int HEADER_STICKY = 0x10;

        private static final boolean DEFAULT_IS_HEADER = false;

        private static final int NO_FIRST_POSITION = -0x01;

        private static final int DEFAULT_HEADER_MARGIN = -0x01;

        private static final int DEFAULT_HEADER_DISPLAY = HEADER_INLINE | HEADER_STICKY;

        public boolean isHeader;

        public int headerDisplay;

        public int sectionManager;

        public int headerMarginEnd;

        public int headerMarginStart;

        public boolean headerStartMarginIsAuto;

        public boolean headerEndMarginIsAuto;

        private int mFirstPosition;

        public LayoutParams(int width, int height) {
            super(width, height);

            isHeader = DEFAULT_IS_HEADER;
        }

        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);

            TypedArray a = c.obtainStyledAttributes(attrs,
                    R.styleable.superslim_LayoutManager);
            isHeader = a.getBoolean(
                    R.styleable.superslim_LayoutManager_slm_isHeader,
                    false);
            headerDisplay = a.getInt(
                    R.styleable.superslim_LayoutManager_slm_headerDisplay,
                    DEFAULT_HEADER_DISPLAY);
            mFirstPosition = a.getInt(
                    R.styleable.superslim_LayoutManager_slm_section_firstPosition,
                    NO_FIRST_POSITION);
            sectionManager = a.getInt(
                    R.styleable.superslim_LayoutManager_slm_section_sectionManager,
                    0);

            // Header margin types can be dimension or integer (enum).
            boolean isDimension;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                TypedValue value = new TypedValue();
                a.getValue(R.styleable.superslim_LayoutManager_slm_section_headerMarginStart,
                        value);
                isDimension = value.type == TypedValue.TYPE_DIMENSION;
            } else {
                isDimension =
                        a.getType(R.styleable.superslim_LayoutManager_slm_section_headerMarginStart)
                                == TypedValue.TYPE_DIMENSION;
            }
            if (isDimension) {
                headerStartMarginIsAuto = false;
                headerMarginStart = a.getDimensionPixelSize(
                        R.styleable.superslim_LayoutManager_slm_section_headerMarginStart, 0);
            } else {
                headerStartMarginIsAuto = true;
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                TypedValue value = new TypedValue();
                a.getValue(R.styleable.superslim_LayoutManager_slm_section_headerMarginEnd, value);
                isDimension = value.type == TypedValue.TYPE_DIMENSION;
            } else {
                isDimension =
                        a.getType(R.styleable.superslim_LayoutManager_slm_section_headerMarginEnd)
                                == TypedValue.TYPE_DIMENSION;
            }
            if (isDimension) {
                headerEndMarginIsAuto = false;
                headerMarginEnd = a.getDimensionPixelSize(
                        R.styleable.superslim_LayoutManager_slm_section_headerMarginEnd, 0);
            } else {
                headerEndMarginIsAuto = true;
            }

            a.recycle();
        }

        public LayoutParams(ViewGroup.LayoutParams other) {
            super(other);
            init(other);
        }

        public boolean areHeaderFlagsSet(int flags) {
            return (headerDisplay & flags) == flags;
        }

        /**
         * Get the first position for the section to which this param's item belongs.
         *
         * @return A value {@literal <=} 0.
         */
        public int getFirstPosition() {
            return mFirstPosition;
        }

        /**
         * Set the first position for the section to which this param's item belongs.
         *
         * @param firstPosition First position of section for this param's item. Must be {@literal
         *                      <=} 0 or an InvalidFirstPositionException runtime exception will be
         *                      thrown.
         */
        public void setFirstPosition(int firstPosition) {
            if (firstPosition < 0) {
                throw new InvalidFirstPositionException();
            }
            mFirstPosition = firstPosition;
        }

        /**
         * Get the first position for the section to which this param's item belongs. Will throw a
         * MissingFirstPositionException runtime exception if the value is {@literal <} 0.
         *
         * @return A value {@literal >=} 0.
         */
        public int getTestedFirstPosition() {
            if (mFirstPosition == NO_FIRST_POSITION) {
                throw new MissingFirstPositionException();
            }
            return mFirstPosition;
        }

        public boolean isHeaderEndAligned() {
            return (headerDisplay & HEADER_ALIGN_END) != 0;
        }

        public boolean isHeaderInline() {
            return (headerDisplay & HEADER_INLINE) != 0;
        }

        public boolean isHeaderOverlay() {
            return (headerDisplay & HEADER_OVERLAY) != 0;
        }

        public boolean isHeaderStartAligned() {
            return (headerDisplay & HEADER_ALIGN_START) != 0;
        }

        public boolean isHeaderSticky() {
            return (headerDisplay & HEADER_STICKY) != 0;
        }

        private void init(ViewGroup.LayoutParams other) {
            if (other instanceof LayoutParams) {
                final LayoutParams lp = (LayoutParams) other;
                isHeader = lp.isHeader;
                headerDisplay = lp.headerDisplay;
                mFirstPosition = lp.mFirstPosition;
                sectionManager = lp.sectionManager;
                headerMarginEnd = lp.headerMarginEnd;
                headerMarginStart = lp.headerMarginStart;
                headerEndMarginIsAuto = lp.headerEndMarginIsAuto;
                headerStartMarginIsAuto = lp.headerStartMarginIsAuto;
            } else {
                isHeader = DEFAULT_IS_HEADER;
                headerDisplay = DEFAULT_HEADER_DISPLAY;
                headerMarginEnd = DEFAULT_HEADER_MARGIN;
                headerMarginStart = DEFAULT_HEADER_MARGIN;
                headerStartMarginIsAuto = true;
                headerEndMarginIsAuto = true;
            }
        }

        private class MissingFirstPositionException extends RuntimeException {

            MissingFirstPositionException() {
                super("Missing section first position.");
            }
        }

        private class InvalidFirstPositionException extends RuntimeException {

            InvalidFirstPositionException() {
                super("Invalid section first position given.");
            }
        }
    }

    protected static class SavedState implements Parcelable {

        public static final Parcelable.Creator<SavedState> CREATOR
                = new Parcelable.Creator<SavedState>() {
            @Override
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            @Override
            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };

        public int anchorPosition;

        public int anchorOffset;

        protected SavedState() {
        }

        protected SavedState(Parcel in) {
            anchorPosition = in.readInt();
            anchorOffset = in.readInt();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(anchorPosition);
            out.writeInt(anchorOffset);
        }
    }

    private class UnknownSectionLayoutException extends RuntimeException {

        public UnknownSectionLayoutException(int id) {
            super("No registered layout for id " + id + ".");
        }
    }
}
