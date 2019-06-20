package fr.pasteur.iah.dendritedynamicstracker.trackmate;

import static fiji.plugin.trackmate.detection.DetectorKeys.KEY_TARGET_CHANNEL;
import static fiji.plugin.trackmate.gui.TrackMateWizard.BIG_FONT;
import static fiji.plugin.trackmate.gui.TrackMateWizard.FONT;
import static fiji.plugin.trackmate.gui.TrackMateWizard.SMALL_FONT;

import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JSlider;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import fiji.plugin.trackmate.Logger;
import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.Settings;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotCollection;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.gui.ConfigurationPanel;
import fiji.plugin.trackmate.gui.TrackMateGUIController;
import fiji.plugin.trackmate.util.JLabelLogger;
import ij.ImagePlus;

public class SkeletonEndPointsDetectorConfigurationPanel extends ConfigurationPanel
{

	private static final long serialVersionUID = 1L;

	private static final ImageIcon ICON_PREVIEW = new ImageIcon( TrackMateGUIController.class.getResource( "images/flag_checked.png" ) );

	private final ImagePlus imp;

	private final Model model;

	private final JSlider sliderChannel;

	private final JButton btnPreview;

	private final Logger localLogger;

	public SkeletonEndPointsDetectorConfigurationPanel( final ImagePlus imp, final Model model )
	{
		this.imp = imp;
		this.model = model;

		final GridBagLayout gridBagLayout = new GridBagLayout();
		gridBagLayout.columnWidths = new int[] { 0, 50, 0, 0 };
		gridBagLayout.rowHeights = new int[] { 0, 0, 0, 0, 0, 0 };
		gridBagLayout.columnWeights = new double[] { 0.0, 1.0, 1.0, Double.MIN_VALUE };
		gridBagLayout.rowWeights = new double[] { 0.0, 0.0, 0.0, 0.0, 0.0, Double.MIN_VALUE };
		setLayout( gridBagLayout );

		final JLabel lblTitle = new JLabel( "Settings for detector:" );
		lblTitle.setFont( FONT );
		final GridBagConstraints gbc_lblTitle = new GridBagConstraints();
		gbc_lblTitle.gridwidth = 3;
		gbc_lblTitle.anchor = GridBagConstraints.WEST;
		gbc_lblTitle.insets = new Insets( 10, 5, 5, 0 );
		gbc_lblTitle.gridx = 0;
		gbc_lblTitle.gridy = 0;
		add( lblTitle, gbc_lblTitle );

		final JLabel lblDetectorName = new JLabel( "Skeleton end-point detector" );
		lblDetectorName.setFont( BIG_FONT );
		final GridBagConstraints gbc_lblSkeletonEndpointsDetector = new GridBagConstraints();
		gbc_lblSkeletonEndpointsDetector.anchor = GridBagConstraints.WEST;
		gbc_lblSkeletonEndpointsDetector.gridwidth = 3;
		gbc_lblSkeletonEndpointsDetector.insets = new Insets( 15, 15, 5, 0 );
		gbc_lblSkeletonEndpointsDetector.gridx = 0;
		gbc_lblSkeletonEndpointsDetector.gridy = 1;
		add( lblDetectorName, gbc_lblSkeletonEndpointsDetector );

		final JLabel lblHelpText = new JLabel();
		lblHelpText.setFont( FONT.deriveFont( Font.ITALIC ) );
		lblHelpText.setText( SkeletonEndPointsDetectorFactory.INFO_TEXT
				.replace( "<br>", "" )
				.replace( "<p>", "<p align=\"justify\">" )
				.replace( "<html>", "<html><p align=\"justify\">" ) );
		final GridBagConstraints gbc_lblHelpTextf = new GridBagConstraints();
		gbc_lblHelpTextf.insets = new Insets( 15, 5, 50, 5 );
		gbc_lblHelpTextf.gridwidth = 3;
		gbc_lblHelpTextf.fill = GridBagConstraints.HORIZONTAL;
		gbc_lblHelpTextf.gridx = 0;
		gbc_lblHelpTextf.gridy = 2;
		add( lblHelpText, gbc_lblHelpTextf );

		final JLabel lblSegmentInChannel = new JLabel( "Segment in channel:" );
		lblSegmentInChannel.setFont( FONT );
		final GridBagConstraints gbc_lblDetectInChannel = new GridBagConstraints();
		gbc_lblDetectInChannel.anchor = GridBagConstraints.WEST;
		gbc_lblDetectInChannel.insets = new Insets( 0, 20, 5, 5 );
		gbc_lblDetectInChannel.gridx = 0;
		gbc_lblDetectInChannel.gridy = 3;
		add( lblSegmentInChannel, gbc_lblDetectInChannel );

		this.sliderChannel = new JSlider();
		final GridBagConstraints gbc_sliderChannel = new GridBagConstraints();
		gbc_sliderChannel.fill = GridBagConstraints.HORIZONTAL;
		gbc_sliderChannel.insets = new Insets( 0, 0, 5, 5 );
		gbc_sliderChannel.gridx = 1;
		gbc_sliderChannel.gridy = 3;
		add( sliderChannel, gbc_sliderChannel );

		final JLabel lblChannel = new JLabel( "1" );
		final GridBagConstraints gbc_lblChannel = new GridBagConstraints();
		gbc_lblChannel.anchor = GridBagConstraints.WEST;
		gbc_lblChannel.insets = new Insets( 0, 15, 5, 15 );
		gbc_lblChannel.gridx = 2;
		gbc_lblChannel.gridy = 3;
		add( lblChannel, gbc_lblChannel );

		sliderChannel.addChangeListener( new ChangeListener()
		{
			@Override
			public void stateChanged( final ChangeEvent e )
			{
				lblChannel.setText( "" + sliderChannel.getValue() );
			}
		} );
		add( sliderChannel );

		btnPreview = new JButton( "Preview", ICON_PREVIEW );
		btnPreview.setFont( SMALL_FONT );
		btnPreview.addActionListener( l -> preview() );
		final GridBagConstraints gbc_btnPreview = new GridBagConstraints();
		gbc_btnPreview.anchor = GridBagConstraints.EAST;
		gbc_btnPreview.gridwidth = 2;
		gbc_btnPreview.insets = new Insets( 50, 0, 0, 5 );
		gbc_btnPreview.gridx = 1;
		gbc_btnPreview.gridy = 4;
		add( btnPreview, gbc_btnPreview );

		/*
		 * Local logger.
		 */

		final JLabelLogger labelLogger = new JLabelLogger();
		final GridBagConstraints gbc_lblLogger = new GridBagConstraints();
		gbc_lblLogger.anchor = GridBagConstraints.NORTHEAST;
		gbc_lblLogger.gridwidth = 3;
		gbc_lblLogger.insets = new Insets( 5, 5, 0, 5 );
		gbc_lblLogger.gridx = 0;
		gbc_lblLogger.gridy = 5;
		add( labelLogger, gbc_lblLogger );
		localLogger = labelLogger.getLogger();

		/*
		 * Deal with channels: the slider and channel labels are only visible if
		 * we find more than one channel.
		 */
		final int n_channels = imp.getNChannels();
		sliderChannel.setMaximum( n_channels );
		sliderChannel.setMinimum( 1 );
		sliderChannel.setValue( imp.getChannel() );

		if ( n_channels <= 1 )
		{
			lblChannel.setVisible( false );
			lblSegmentInChannel.setVisible( false );
			sliderChannel.setVisible( false );
		}
		else
		{
			lblChannel.setVisible( true );
			lblSegmentInChannel.setVisible( true );
			sliderChannel.setVisible( true );
		}
	}

	@Override
	public void setSettings( final Map< String, Object > settings )
	{
		sliderChannel.setValue( ( Integer ) settings.get( KEY_TARGET_CHANNEL ) );
	}

	@Override
	public Map< String, Object > getSettings()
	{
		final HashMap< String, Object > lSettings = new HashMap<>( 1 );
		final int targetChannel = sliderChannel.getValue();
		lSettings.put( KEY_TARGET_CHANNEL, targetChannel );
		return lSettings;
	}

	@Override
	public void clean()
	{}

	/**
	 * Launch detection on the current frame.
	 */
	private void preview()
	{
		btnPreview.setEnabled( false );
		new Thread( "TrackMate preview detection thread" )
		{
			@Override
			public void run()
			{
				final Settings lSettings = new Settings();
				lSettings.setFrom( imp );
				final int frame = imp.getFrame() - 1;
				lSettings.tstart = frame;
				lSettings.tend = frame;

				lSettings.detectorFactory = new SkeletonEndPointsDetectorFactory<>();
				lSettings.detectorSettings = getSettings();

				final TrackMate trackmate = new TrackMate( lSettings );
				trackmate.getModel().setLogger( localLogger );

				final boolean detectionOk = trackmate.execDetection();
				if ( !detectionOk )
				{
					localLogger.error( trackmate.getErrorMessage() );
					return;
				}
				localLogger.log( "Found " + trackmate.getModel().getSpots().getNSpots( false ) + " spots." );

				// Wrap new spots in a list.
				final SpotCollection newspots = trackmate.getModel().getSpots();
				final Iterator< Spot > it = newspots.iterator( frame, false );
				final ArrayList< Spot > spotsToCopy = new ArrayList<>( newspots.getNSpots( frame, false ) );
				while ( it.hasNext() )
				{
					spotsToCopy.add( it.next() );
				}
				// Pass new spot list to model.
				model.getSpots().put( frame, spotsToCopy );
				// Make them visible
				for ( final Spot spot : spotsToCopy )
				{
					spot.putFeature( SpotCollection.VISIBLITY, SpotCollection.ONE );
				}
				// Generate event for listener to reflect changes.
				model.setSpots( model.getSpots(), true );

				btnPreview.setEnabled( true );

			}
		}.start();
	}
}
