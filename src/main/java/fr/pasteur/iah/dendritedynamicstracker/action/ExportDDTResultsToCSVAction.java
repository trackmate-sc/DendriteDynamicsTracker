package fr.pasteur.iah.dendritedynamicstracker.action;

import java.awt.Frame;

import javax.swing.ImageIcon;

import org.scijava.plugin.Plugin;

import fiji.plugin.trackmate.SelectionModel;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.action.AbstractTMAction;
import fiji.plugin.trackmate.action.TrackMateAction;
import fiji.plugin.trackmate.action.TrackMateActionFactory;
import fiji.plugin.trackmate.gui.displaysettings.DisplaySettings;
import fr.pasteur.iah.dendritedynamicstracker.DendriteDynamicsCSVExporter;

public class ExportDDTResultsToCSVAction extends AbstractTMAction
{

	public static final ImageIcon ICON = new ImageIcon( ExportDDTResultsToCSVAction.class.getResource( "images/page_white_excel.png" ) );

	public static final String NAME = "Export Dendrite Dynamics Tracker results to CSV";

	public static final String KEY = "EXPORT_DDT_TO_CSV";

	public static final String INFO_TEXT = "<html>"
			+ "This action exports the results of the <b>Dendrite Dynamics Tracker</b>, present "
			+ "in this TrackMate instance, as multiple CSV files. "
			+ "<p> "
			+ "<p> "
			+ "The files exported are the same than the ones created when checking the "
			+ "<it>Export branch lengths and statistics to CSV files</it> box in the "
			+ "Dendrite Dynamics Tracker command window. "
			+ "</html>";

	@Override
	public void execute( final TrackMate trackmate, final SelectionModel selectionModel, final DisplaySettings displaySettings, final Frame parent )
	{
		logger.log( "Exporting to CSV files...\n" );
		final DendriteDynamicsCSVExporter exporter = new DendriteDynamicsCSVExporter( trackmate );
		if ( !exporter.checkInput() || !exporter.process() )
			logger.error( "Error while exporting results:\n" + exporter.getErrorMessage() );
		else
			logger.log( "Done.\n" );
	}

	@Plugin( type = TrackMateActionFactory.class )
	public static class Factory implements TrackMateActionFactory
	{

		@Override
		public String getInfoText()
		{
			return INFO_TEXT;
		}

		@Override
		public String getKey()
		{
			return KEY;
		}

		@Override
		public ImageIcon getIcon()
		{
			return ICON;
		}

		@Override
		public String getName()
		{
			return NAME;
		}

		@Override
		public TrackMateAction create()
		{
			return new ExportDDTResultsToCSVAction();
		}
	}
}
