package fr.pasteur.iah.dendritedynamicstracker.skeleton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.imglib2.Localizable;
import net.imglib2.Point;
import net.imglib2.RealLocalizable;
import net.imglib2.img.Img;
import net.imglib2.type.numeric.integer.UnsignedByteType;

public class SkeletonAnalysis
{

	public final Img< UnsignedByteType > taggedSkeleton;

	private final List< SkeletonAnalysis.Junction > junctions;

	private final List< Branch > branches;

	SkeletonAnalysis( 
			final Img< UnsignedByteType > taggedSkeleton, 
			final List< Junction > junctions, 
			final List< Branch > branches )
	{
		this.taggedSkeleton = taggedSkeleton;
		this.junctions = junctions;
		this.branches = branches;
	}

	public List< SkeletonAnalysis.Junction > getJunctions()
	{
		return Collections.unmodifiableList( junctions );
	}

	public List< Branch > getBranches()
	{
		return Collections.unmodifiableList( branches );
	}

	public static class Branch
	{

		private final Point endPoint;

		private final Point junctionPoint;

		private final Junction junction;

		Branch( final Localizable endPoint, final Localizable junctionPoint, final Junction junction )
		{
			this.endPoint = new Point( endPoint );
			this.junctionPoint = new Point( junctionPoint );
			this.junction = junction;
		}
		
		public Localizable getEndPoint()
		{
			return endPoint;
		}
		
		public Localizable getJunctionPoint()
		{
			return junctionPoint;
		}
		
		public Junction getJunction()
		{
			return junction;
		}

		@Override
		public String toString()
		{
			return "Branch_@" + endPoint + "->" + junctionPoint + 
					( ( null == junction ) ? "_NoJunction" : ( "_J" + junction.id() ) );
		}

	}

	public static class Junction implements RealLocalizable
	{

		private final int id;

		private double[] sumPos;

		private int nPos = 0;

		private final Localizable pixel;

		private final List< Branch > branches;

		Junction( final int id, final Localizable pixel )
		{
			this.id = id;
			this.sumPos = new double[ pixel.numDimensions() ];
			final Point r = Point.wrap( new long[ pixel.numDimensions() ] );
			r.setPosition( pixel );
			this.pixel = r;
			this.branches = new ArrayList<>();
		}

		void increment( final Localizable pos )
		{
			nPos++;
			for ( int d = 0; d < sumPos.length; d++ )
				sumPos[ d ] += pos.getDoublePosition( d );
		}

		/**
		 * Returns the position of one pixel of the junction. The position of
		 * this pixel in the junction is random.
		 */
		public Localizable getPixel()
		{
			return pixel;
		}

		public int getSize()
		{
			return nPos;
		}

		public int id()
		{
			return id;
		}

		@Override
		public int numDimensions()
		{
			return sumPos.length;
		}

		@Override
		public void localize( final float[] position )
		{
			for ( int d = 0; d < position.length; d++ )
				position[ d ] = ( float ) ( sumPos[ d ] / nPos );
		}

		@Override
		public void localize( final double[] position )
		{
			for ( int d = 0; d < position.length; d++ )
				position[ d ] = sumPos[ d ] / nPos;
		}

		@Override
		public float getFloatPosition( final int d )
		{
			return ( float ) getDoublePosition( d );
		}

		@Override
		public double getDoublePosition( final int d )
		{
			return sumPos[ d ] / nPos;
		}

		@Override
		public String toString()
		{
			return "Junction-" + id + "_Npixels" + nPos + "_Nbranches" + branches.size() + "_@" + pixel;
		}

		void addBranch( final Branch branch )
		{
			branches.add( branch );
		}
	}
}
