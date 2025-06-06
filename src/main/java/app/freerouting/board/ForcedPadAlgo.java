package app.freerouting.board;

import app.freerouting.datastructures.TimeLimit;
import app.freerouting.geometry.planar.*;
import app.freerouting.logger.FRLogger;

import java.util.Collection;
import java.util.LinkedList;

/**
 * Class with functions for checking and inserting pads with eventually shoving aside obstacle
 * traces.
 */
public class ForcedPadAlgo
{

  private final RoutingBoard board;

  /**
   * Creates a new instance of ForcedPadAlgo
   */
  public ForcedPadAlgo(RoutingBoard p_board)
  {
    board = p_board;
  }

  private static TileShape calc_check_shape_for_from_side(TileShape p_shape, Point p_shape_center, Line p_border_line)
  {
    FloatPoint shape_center = p_shape_center.to_float();
    FloatPoint offset_projection = shape_center.projection_approx(p_border_line);
    // Make sure, that direction restrictions are retained.
    Line[] line_arr = new Line[3];
    Direction curr_dir = p_border_line.direction();
    line_arr[0] = new Line(p_shape_center, curr_dir);
    line_arr[1] = new Line(p_shape_center, curr_dir.turn_45_degree(2));
    line_arr[2] = new Line(offset_projection.round(), curr_dir);
    Polyline check_line = new Polyline(line_arr);
    return check_line.offset_shape(1, 0);
  }

  /**
   * Checks, if p_line is in front of p_pad_shape when shoving from p_from_side
   */
  private static boolean in_front_of_pad(Line p_line, TileShape p_pad_shape, int p_from_side, int p_width, boolean p_with_sides)
  {
    if (!p_pad_shape.is_IntOctagon())
    {
      // only implemented for octagons
      return true;
    }
    IntOctagon pad_octagon = p_pad_shape.bounding_octagon();
    if (!(p_line.a instanceof IntPoint line_a && p_line.b instanceof IntPoint line_b))
    {
      // not implemented
      return true;
    }

    double diag_width = p_width * Math.sqrt(2);

    boolean result;
    switch (p_from_side)
    {
      case 0 ->
      {
        result = Math.min(line_a.y, line_b.y) >= pad_octagon.topY + p_width || Math.max(line_a.x - line_a.y, line_b.x - line_b.y) <= pad_octagon.upperLeftDiagonalX - diag_width || Math.min(line_a.x + line_a.y, line_b.x + line_b.x) >= pad_octagon.upperRightDiagonalX + diag_width;
        if (p_with_sides && !result)
        {
          result = Math.max(line_a.x, line_b.x) <= pad_octagon.leftX - p_width && Math.min(line_a.x - line_a.y, line_b.x - line_b.y) <= pad_octagon.upperLeftDiagonalX - diag_width || Math.min(line_a.x, line_b.x) >= pad_octagon.rightX + p_width && Math.min(line_a.x + line_a.y, line_b.x + line_b.y) >= pad_octagon.upperRightDiagonalX + diag_width;
        }
      }
      case 1 ->
      {
        result = Math.min(line_a.y, line_b.y) >= pad_octagon.topY + p_width || Math.max(line_a.x - line_a.y, line_b.x - line_b.y) <= pad_octagon.upperLeftDiagonalX - diag_width || Math.max(line_a.x, line_b.x) <= pad_octagon.leftX - p_width;
        if (p_with_sides && !result)
        {
          result = Math.min(line_a.x, line_b.x) <= pad_octagon.leftX - p_width && Math.max(line_a.x + line_a.y, line_b.x + line_b.y) <= pad_octagon.lowerLeftDiagonalX - diag_width || Math.max(line_a.y, line_b.y) >= pad_octagon.topY + p_width && Math.min(line_a.x + line_a.y, line_b.x + line_b.y) >= pad_octagon.upperRightDiagonalX + diag_width;
        }
      }
      case 2 ->
      {
        result = Math.max(line_a.x, line_b.x) <= pad_octagon.leftX - p_width || Math.max(line_a.x - line_a.y, line_b.x - line_b.y) <= pad_octagon.upperLeftDiagonalX - diag_width || Math.max(line_a.x + line_a.y, line_b.x + line_b.y) <= pad_octagon.lowerLeftDiagonalX - diag_width;
        if (p_with_sides && !result)
        {
          result = Math.max(line_a.y, line_b.y) <= pad_octagon.bottomY - p_width && Math.min(line_a.x + line_a.y, line_b.x + line_b.y) <= pad_octagon.lowerLeftDiagonalX - diag_width || Math.min(line_a.y, line_b.y) >= pad_octagon.topY + p_width && Math.min(line_a.x - line_a.y, line_b.x - line_b.y) <= pad_octagon.upperLeftDiagonalX - diag_width;
        }
      }
      case 3 ->
      {
        result = Math.max(line_a.x, line_b.x) <= pad_octagon.leftX - p_width || Math.max(line_a.y, line_b.y) <= pad_octagon.bottomY - p_width || Math.max(line_a.x + line_a.y, line_b.x + line_b.y) <= pad_octagon.lowerLeftDiagonalX - diag_width;
        if (p_with_sides && !result)
        {
          result = Math.min(line_a.y, line_b.y) <= pad_octagon.bottomY - p_width && Math.min(line_a.x - line_a.y, line_b.x - line_b.y) >= pad_octagon.lowerRightDiagonalX + diag_width || Math.min(line_a.x, line_b.x) <= pad_octagon.leftX - p_width && Math.max(line_a.x - line_a.y, line_b.x - line_b.y) <= pad_octagon.upperLeftDiagonalX - diag_width;
        }
      }
      case 4 ->
      {
        result = Math.max(line_a.y, line_b.y) <= pad_octagon.bottomY - p_width || Math.max(line_a.x + line_a.y, line_b.x + line_b.y) <= pad_octagon.lowerLeftDiagonalX - diag_width || Math.min(line_a.x - line_a.y, line_b.x - line_b.y) >= pad_octagon.lowerRightDiagonalX + diag_width;
        if (p_with_sides && !result)
        {
          result = Math.min(line_a.x, line_b.x) >= pad_octagon.rightX + p_width && Math.max(line_a.x - line_a.y, line_b.x - line_b.y) >= pad_octagon.lowerRightDiagonalX + diag_width || Math.max(line_a.x, line_b.x) <= pad_octagon.leftX - p_width && Math.min(line_a.x + line_a.y, line_b.x + line_b.y) <= pad_octagon.lowerLeftDiagonalX - diag_width;
        }
      }
      case 5 ->
      {
        result = Math.max(line_a.y, line_b.y) <= pad_octagon.bottomY - p_width || Math.min(line_a.x, line_b.x) >= pad_octagon.rightX + p_width || Math.min(line_a.x - line_a.y, line_b.x - line_b.y) >= pad_octagon.lowerRightDiagonalX + diag_width;
        if (p_with_sides && !result)
        {
          result = Math.max(line_a.x, line_b.x) >= pad_octagon.rightX + p_width && Math.min(line_a.x + line_a.y, line_b.x + line_b.y) >= pad_octagon.upperRightDiagonalX + diag_width || Math.min(line_a.y, line_b.y) <= pad_octagon.bottomY - p_width && Math.max(line_a.x + line_a.y, line_b.x + line_b.y) <= pad_octagon.lowerLeftDiagonalX - diag_width;
        }
      }
      case 6 ->
      {
        result = Math.min(line_a.x, line_b.x) >= pad_octagon.rightX + p_width || Math.min(line_a.x + line_a.y, line_b.x + line_b.y) >= pad_octagon.upperRightDiagonalX + diag_width || Math.min(line_a.x - line_a.y, line_b.x - line_b.y) >= pad_octagon.lowerRightDiagonalX + diag_width;
        if (p_with_sides && !result)
        {
          result = Math.max(line_a.y, line_b.y) <= pad_octagon.bottomY - p_width && Math.max(line_a.x - line_a.y, line_b.x - line_b.y) >= pad_octagon.lowerRightDiagonalX + diag_width || Math.min(line_a.y, line_b.y) >= pad_octagon.topY + p_width && Math.max(line_a.x + line_a.y, line_b.x + line_b.y) >= pad_octagon.upperRightDiagonalX + diag_width;
        }
      }
      case 7 ->
      {
        result = Math.min(line_a.y, line_b.y) >= pad_octagon.topY + p_width || Math.min(line_a.x + line_a.y, line_b.x + line_b.y) >= pad_octagon.upperRightDiagonalX + diag_width || Math.min(line_a.x, line_b.x) >= pad_octagon.rightX + p_width;
        if (p_with_sides && !result)
        {
          result = Math.max(line_a.y, line_b.y) >= pad_octagon.topY + p_width && Math.max(line_a.x - line_a.y, line_b.x - line_b.y) <= pad_octagon.upperLeftDiagonalX - diag_width || Math.max(line_a.x, line_b.x) >= pad_octagon.rightX + p_width && Math.min(line_a.x - line_a.y, line_b.x - line_b.y) >= pad_octagon.lowerRightDiagonalX + diag_width;
        }
      }
      default ->
      {
        FRLogger.warn("ForcedPadAlgo.in_front_of_pad: p_from_side out of range");
        result = true;
      }
    }

    return result;
  }

  /**
   * Checks, if possible obstacle traces can be shoved aside, so that a pad with the input
   * parameters can be inserted without clearance violations. Returns false, if the check failed. If
   * p_ignore_items != null, items in this list are not checked, If p_check_only_front only trace
   * obstacles in the direction from p_from_side are checked for performance reasons. This is the
   * cave when moving drill_items
   */
  public CheckDrillResult check_forced_pad(TileShape p_pad_shape, CalcFromSide p_from_side, int p_layer, int[] p_net_no_arr, int p_cl_type, boolean p_copper_sharing_allowed, Collection<Item> p_ignore_items, int p_max_recursion_depth, int p_max_via_recursion_depth, boolean p_check_only_front, TimeLimit p_time_limit)
  {
    if (!p_pad_shape.is_contained_in(board.get_bounding_box()))
    {
      this.board.set_shove_failing_obstacle(board.get_outline());
      return CheckDrillResult.NOT_DRILLABLE;
    }
    ShapeSearchTree search_tree = this.board.search_tree_manager.get_default_tree();
    ShapeTraceEntries shape_entries = new ShapeTraceEntries(p_pad_shape, p_layer, p_net_no_arr, p_cl_type, p_from_side, board);
    Collection<Item> obstacles = search_tree.overlapping_items_with_clearance(p_pad_shape, p_layer, new int[0], p_cl_type);

    if (p_ignore_items != null)
    {
      obstacles.removeAll(p_ignore_items);
    }
    boolean obstacles_shovable = shape_entries.store_items(obstacles, true, p_copper_sharing_allowed);
    if (!obstacles_shovable)
    {
      this.board.set_shove_failing_obstacle(shape_entries.get_found_obstacle());
      return CheckDrillResult.NOT_DRILLABLE;
    }

    // check, if the obstacle vias can be shoved

    for (Via curr_shove_via : shape_entries.shove_via_list)
    {
      if (p_max_via_recursion_depth <= 0)
      {
        this.board.set_shove_failing_obstacle(curr_shove_via);
        return CheckDrillResult.NOT_DRILLABLE;
      }
      IntPoint[] new_via_center = MoveDrillItemAlgo.try_shove_via_points(p_pad_shape, p_layer, curr_shove_via, p_cl_type, false, board);

      if (new_via_center.length == 0)
      {
        this.board.set_shove_failing_obstacle(curr_shove_via);
        return CheckDrillResult.NOT_DRILLABLE;
      }
      Vector delta = new_via_center[0].difference_by(curr_shove_via.get_center());
      Collection<Item> ignore_items = new LinkedList<>();
      if (!MoveDrillItemAlgo.check(curr_shove_via, delta, p_max_recursion_depth, p_max_via_recursion_depth - 1, ignore_items, this.board, p_time_limit))
      {
        return CheckDrillResult.NOT_DRILLABLE;
      }
    }
    CheckDrillResult result = CheckDrillResult.DRILLABLE;
    if (p_copper_sharing_allowed)
    {
      for (Item curr_obstacle : obstacles)
      {
        if (curr_obstacle instanceof Pin)
        {
          result = CheckDrillResult.DRILLABLE_WITH_ATTACH_SMD;
          break;
        }
      }
    }
    int trace_piece_count = shape_entries.substitute_trace_count();
    if (trace_piece_count == 0)
    {
      return result;
    }
    if (p_max_recursion_depth <= 0)
    {
      this.board.set_shove_failing_obstacle(shape_entries.get_found_obstacle());
      return CheckDrillResult.NOT_DRILLABLE;
    }
    if (shape_entries.stack_depth() > 1)
    {
      this.board.set_shove_failing_obstacle(shape_entries.get_found_obstacle());
      return CheckDrillResult.NOT_DRILLABLE;
    }
    ShoveTraceAlgo shove_trace_algo = new ShoveTraceAlgo(board);
    boolean is_orthogonal_mode = p_pad_shape instanceof IntBox;
    for (; ; )
    {
      PolylineTrace curr_substitute_trace = shape_entries.next_substitute_trace_piece();
      if (curr_substitute_trace == null)
      {
        break;
      }
      for (int i = 0; i < curr_substitute_trace.tile_shape_count(); ++i)
      {
        Line curr_line = curr_substitute_trace.polyline().arr[i + 1];
        Direction curr_dir = curr_line.direction();
        boolean is_in_front;
        if (p_check_only_front)
        {
          is_in_front = in_front_of_pad(curr_line, p_pad_shape, p_from_side.no, curr_substitute_trace.get_half_width(), true);
        }
        else
        {
          is_in_front = true;
        }
        if (is_in_front)
        {
          CalcShapeAndFromSide curr = new CalcShapeAndFromSide(curr_substitute_trace, i, is_orthogonal_mode, true);
          if (!shove_trace_algo.check(curr.shape, curr.from_side, curr_dir, p_layer, curr_substitute_trace.net_no_arr, curr_substitute_trace.clearance_class_no(), p_max_recursion_depth - 1, p_max_via_recursion_depth, 0, p_time_limit))
          {
            return CheckDrillResult.NOT_DRILLABLE;
          }
        }
      }
    }
    return result;
  }

  /**
   * Shoves aside traces, so that a pad with the input parameters can be inserted without clearance
   * violations. Returns false, if the shove failed. In this case the database may be damaged, so
   * that an undo becomes necessary.
   */
  boolean forced_pad(TileShape p_pad_shape, CalcFromSide p_from_side, int p_layer, int[] p_net_no_arr, int p_cl_type, boolean p_copper_sharing_allowed, Collection<Item> p_ignore_items, int p_max_recursion_depth, int p_max_via_recursion_depth)
  {
    if (p_pad_shape.is_empty())
    {
      FRLogger.warn("ShoveTraceAux.forced_pad: p_pad_shape is empty");
      return true;
    }
    if (!p_pad_shape.is_contained_in(board.get_bounding_box()))
    {
      this.board.set_shove_failing_obstacle(board.get_outline());
      return false;
    }
    if (!MoveDrillItemAlgo.shove_vias(p_pad_shape, p_from_side, p_layer, p_net_no_arr, p_cl_type, p_ignore_items, p_max_recursion_depth, p_max_via_recursion_depth, false, this.board))
    {
      return false;
    }
    ShapeSearchTree search_tree = this.board.search_tree_manager.get_default_tree();
    ShapeTraceEntries shape_entries = new ShapeTraceEntries(p_pad_shape, p_layer, p_net_no_arr, p_cl_type, p_from_side, board);
    Collection<Item> obstacles = search_tree.overlapping_items_with_clearance(p_pad_shape, p_layer, new int[0], p_cl_type);
    if (p_ignore_items != null)
    {
      obstacles.removeAll(p_ignore_items);
    }
    boolean obstacles_shovable = shape_entries.store_items(obstacles, true, p_copper_sharing_allowed) && shape_entries.shove_via_list.isEmpty();
    if (!obstacles_shovable)
    {
      this.board.set_shove_failing_obstacle(shape_entries.get_found_obstacle());
      return false;
    }
    int trace_piece_count = shape_entries.substitute_trace_count();
    if (trace_piece_count == 0)
    {
      return true;
    }
    if (p_max_recursion_depth <= 0)
    {
      this.board.set_shove_failing_obstacle(shape_entries.get_found_obstacle());
      return false;
    }
    boolean tails_exist_before = board.contains_trace_tails(obstacles, p_net_no_arr);
    shape_entries.cutout_traces(obstacles);
    boolean is_orthogonal_mode = p_pad_shape instanceof IntBox;
    ShoveTraceAlgo shove_trace_algo = new ShoveTraceAlgo(this.board);
    for (; ; )
    {
      PolylineTrace curr_substitute_trace = shape_entries.next_substitute_trace_piece();
      if (curr_substitute_trace == null)
      {
        break;
      }
      if (curr_substitute_trace
          .first_corner()
          .equals(curr_substitute_trace.last_corner()))
      {
        continue;
      }
      int[] curr_net_no_arr = curr_substitute_trace.net_no_arr;
      for (int i = 0; i < curr_substitute_trace.tile_shape_count(); ++i)
      {
        CalcShapeAndFromSide curr = new CalcShapeAndFromSide(curr_substitute_trace, i, is_orthogonal_mode, false);
        if (!shove_trace_algo.insert(curr.shape, curr.from_side, p_layer, curr_net_no_arr, curr_substitute_trace.clearance_class_no(), p_ignore_items, p_max_recursion_depth - 1, p_max_via_recursion_depth, 0))
        {
          return false;
        }
      }
      for (int i = 0; i < curr_substitute_trace.corner_count(); ++i)
      {
        board.join_changed_area(curr_substitute_trace
            .polyline()
            .corner_approx(i), p_layer);
      }
      Point[] end_corners = null;
      if (!tails_exist_before)
      {
        end_corners = new Point[2];
        end_corners[0] = curr_substitute_trace.first_corner();
        end_corners[1] = curr_substitute_trace.last_corner();
      }
      board.insert_item(curr_substitute_trace);
      IntOctagon opt_area;
      if (board.changed_area != null)
      {
        opt_area = board.changed_area.get_area(p_layer);
      }
      else
      {
        opt_area = null;
      }

      try
      {
        curr_substitute_trace.normalize(opt_area);
      } catch (Exception e)
      {
        FRLogger.error("Couldn't normalize trace.", e);
      }

      if (!tails_exist_before)
      {
        for (int i = 0; i < 2; ++i)
        {
          Trace tail = board.get_trace_tail(end_corners[i], p_layer, curr_net_no_arr);
          if (tail != null)
          {
            board.remove_items(tail.get_connection_items(Item.StopConnectionOption.VIA));
            for (int curr_net_no : curr_net_no_arr)
            {
              board.combine_traces(curr_net_no);
            }
          }
        }
      }
    }
    return true;
  }

  /**
   * Looks for a side of p_shape, so that a trace line from the shape center to the nearest point on
   * this side does not conflict with any obstacles.
   */
  CalcFromSide calc_from_side(TileShape p_shape, Point p_shape_center, int p_layer, int p_offset, int p_cl_class)
  {
    int[] empty_arr = new int[0];
    TileShape offset_shape = (TileShape) p_shape.offset(p_offset);
    for (int i = 0; i < offset_shape.border_line_count(); ++i)
    {
      TileShape check_shape = calc_check_shape_for_from_side(p_shape, p_shape_center, offset_shape.border_line(i));

      if (board.check_trace_shape(check_shape, p_layer, empty_arr, p_cl_class, null))
      {
        return new CalcFromSide(i, null);
      }
    }
    // try second check without clearance
    for (int i = 0; i < offset_shape.border_line_count(); ++i)
    {
      TileShape check_shape = calc_check_shape_for_from_side(p_shape, p_shape_center, offset_shape.border_line(i));
      if (board.check_trace_shape(check_shape, p_layer, empty_arr, 0, null))
      {
        return new CalcFromSide(i, null);
      }
    }
    return CalcFromSide.NOT_CALCULATED;
  }

  public enum CheckDrillResult
  {
    DRILLABLE, DRILLABLE_WITH_ATTACH_SMD, NOT_DRILLABLE
  }
}