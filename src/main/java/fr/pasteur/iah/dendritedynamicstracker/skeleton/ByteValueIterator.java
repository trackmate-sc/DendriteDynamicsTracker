package fr.pasteur.iah.dendritedynamicstracker.skeleton;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;

import net.imglib2.Cursor;
import net.imglib2.Interval;
import net.imglib2.Localizable;
import net.imglib2.Point;
import net.imglib2.RandomAccess;
import net.imglib2.algorithm.neighborhood.Neighborhood;
import net.imglib2.algorithm.neighborhood.RectangleShape;
import net.imglib2.algorithm.neighborhood.RectangleShape.NeighborhoodsAccessible;
import net.imglib2.img.Img;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;

public class ByteValueIterator implements Iterator< Localizable >
{

	private final RandomAccess< Neighborhood< UnsignedByteType > > raNeighborhood;

	/**
	 * It's important that this is a list and made of {@link Point}s because we
	 * want to test for equality based on coordinates, so we need to invoke que
	 * {@link #equals(Object)} method of {@link Point}.
	 */
	private final Collection< Point > visited;

	private final Deque< Point > toVisit;

	private final int lookupValue;

	private final Interval interval;

	ByteValueIterator( final Img< UnsignedByteType > taggedSkeleton, final Localizable start, final int lookupValue )
	{
		this.lookupValue = lookupValue;
		final NeighborhoodsAccessible< UnsignedByteType > neighborhoods = new RectangleShape( 1, true ).neighborhoodsRandomAccessible( taggedSkeleton );
		this.raNeighborhood = neighborhoods.randomAccess( taggedSkeleton );
		this.visited = new ArrayList<>();
		this.toVisit = new ArrayDeque<>();
		toVisit.push( new Point( start ) );
		this.interval = taggedSkeleton;
	}

	private void visit( final Point point )
	{
		visited.add( point );
		raNeighborhood.setPosition( point );
		final Cursor< UnsignedByteType > cursor = raNeighborhood.get().localizingCursor();
		while ( cursor.hasNext() )
		{
			cursor.fwd();
			if ( !Intervals.contains( interval, cursor ) )
				continue;

			final int val = cursor.get().get();
			if ( val == lookupValue )
			{
				final Point current = new Point( cursor );
				// Did we visit this position already?
				if ( visited.contains( current ) )
					continue;
				
				toVisit.add( current );
			}
		}
	}

	@Override
	public boolean hasNext()
	{
		return !toVisit.isEmpty();
	}

	@Override
	public Localizable next()
	{
		final Point next = toVisit.pop();
		visit( next );
		return next;
	}
}
