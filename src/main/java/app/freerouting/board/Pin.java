package app.freerouting.board;

import app.freerouting.boardgraphics.GraphicsContext;
import app.freerouting.core.LogicalPart;
import app.freerouting.core.Package;
import app.freerouting.core.Padstack;
import app.freerouting.geometry.planar.*;
import app.freerouting.geometry.planar.Point;
import app.freerouting.geometry.planar.Shape;
import app.freerouting.geometry.planar.Vector;
import app.freerouting.logger.FRLogger;
import app.freerouting.management.TextManager;

import java.awt.*;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.*;

/**
 * Class describing the functionality of an electrical Item on the board with a shape on 1 or
 * several layers.
 */
public class Pin extends DrillItem implements Serializable
{
  /**
   * The number of this pin in its component (starting with 0).
   */
  public final int pin_no;
  /**
   * The pin, this pin was changed to by swapping or this pin, if no pin swap occurred.
   */
  private Pin changed_to = this;
  private transient Shape[] precalculated_shapes;

  /**
   * Creates a new instance of Pin with the input parameters. (p_to_layer - p_from_layer + 1) shapes
   * must be provided. p_pin_no is the number of the pin in its component (starting with 0).
   */
  Pin(int p_component_no, int p_pin_no, int[] p_net_no_arr, int p_clearance_type, int p_id_no, FixedState p_fixed_state, BasicBoard p_board)
  {
    super(null, p_net_no_arr, p_clearance_type, p_id_no, p_component_no, p_fixed_state, p_board);

    this.pin_no = p_pin_no;
  }

  /**
   * Calculates the relative location of this pin to its component.
   */
  public Vector relative_location()
  {
    Component component = board.components.get(this.get_component_no());
    Package lib_package = component.get_package();
    Package.Pin package_pin = lib_package.get_pin(this.pin_no);
    Vector rel_location = package_pin.relative_location;
    double component_rotation = component.get_rotation_in_degree();
    if (!component.placed_on_front() && !board.components.get_flip_style_rotate_first())
    {
      rel_location = package_pin.relative_location.mirror_at_y_axis();
    }
    if (component_rotation % 90 == 0)
    {
      int component_ninety_degree_factor = ((int) component_rotation) / 90;
      if (component_ninety_degree_factor != 0)
      {
        rel_location = rel_location.turn_90_degree(component_ninety_degree_factor);
      }
    }
    else
    {
      // rotation may be not exact
      FloatPoint location_approx = rel_location.to_float();
      location_approx = location_approx.rotate(Math.toRadians(component_rotation), FloatPoint.ZERO);
      rel_location = location_approx
          .round()
          .difference_by(Point.ZERO);
    }
    if (!component.placed_on_front() && board.components.get_flip_style_rotate_first())
    {
      rel_location = rel_location.mirror_at_y_axis();
    }
    return rel_location;
  }

  @Override
  public Point get_center()
  {
    Point pin_center = super.get_center();
    if (pin_center == null)
    {

      // Calculate the pin center.
      Component component = board.components.get(this.get_component_no());
      pin_center = component
          .get_location()
          .translate_by(this.relative_location());

      // check that the pin center is inside the pin shape and correct it eventually

      Padstack padstack = get_padstack();
      int from_layer = padstack.from_layer();
      int to_layer = padstack.to_layer();
      Shape curr_shape = null;
      for (int i = 0; i < to_layer - from_layer + 1; ++i)
      {
        curr_shape = this.get_shape(i);
        if (curr_shape != null)
        {
          break;
        }
      }
      if (curr_shape == null)
      {
        FRLogger.warn("Pin: At least 1 shape != null expected");
      }
      else if (!curr_shape.contains_inside(pin_center))
      {
        pin_center = curr_shape
            .centre_of_gravity()
            .round();
      }
      this.set_center(pin_center);
    }
    return pin_center;
  }

  @Override
  public Padstack get_padstack()
  {
    Component component = board.components.get(get_component_no());
    if (component == null)
    {
      FRLogger.warn("Pin.get_padstack; component not found");
      return null;
    }
    int padstack_no = component
        .get_package()
        .get_pin(pin_no).padstack_no;
    return board.library.padstacks.get(padstack_no);
  }

  @Override
  public Item copy(int p_id_no)
  {
    int[] curr_net_no_arr = new int[this.net_count()];
    for (int i = 0; i < curr_net_no_arr.length; ++i)
    {
      curr_net_no_arr[i] = get_net_no(i);
    }
    return new Pin(get_component_no(), this.pin_no, curr_net_no_arr, clearance_class_no(), p_id_no, get_fixed_state(), board);
  }

  /**
   * Return the name of this pin in the package of this component.
   */
  public String name()
  {
    Component component = board.components.get(this.get_component_no());
    if (component == null)
    {
      FRLogger.warn("Pin.name: component not found");
      return null;
    }
    return component
        .get_package()
        .get_pin(pin_no).name;
  }

  /**
   * Gets index of this pin in the library package of the pins component.
   */
  public int get_index_in_package()
  {
    return pin_no;
  }

  @Override
  public Shape get_shape(int p_index)
  {
    Padstack padstack = get_padstack();
    if (this.precalculated_shapes == null)
    {
      // all shapes have to be calculated  at once, because otherwise calculation
      // of from_layer and to_layer may not be correct
      this.precalculated_shapes = new Shape[padstack.to_layer() - padstack.from_layer() + 1];

      Component component = board.components.get(this.get_component_no());
      if (component == null)
      {
        FRLogger.warn("Pin.get_shape: component not found");
        return null;
      }
      Package lib_package = component.get_package();
      if (lib_package == null)
      {
        FRLogger.warn("Pin.get_shape: package not found");
        return null;
      }
      Package.Pin package_pin = lib_package.get_pin(this.pin_no);
      if (package_pin == null)
      {
        FRLogger.warn("Pin.get_shape: pin_no out of range");
        return null;
      }
      Vector rel_location = package_pin.relative_location;
      double component_rotation = component.get_rotation_in_degree();

      boolean mirror_at_y_axis = !component.placed_on_front() && !board.components.get_flip_style_rotate_first();

      if (mirror_at_y_axis)
      {
        rel_location = package_pin.relative_location.mirror_at_y_axis();
      }

      Vector component_translation = component
          .get_location()
          .difference_by(Point.ZERO);

      for (int index = 0; index < this.precalculated_shapes.length; ++index)
      {

        int padstack_layer = get_padstack_layer(index);

        ConvexShape curr_shape = padstack.get_shape(padstack_layer);
        if (curr_shape == null)
        {
          continue;
        }
        double pin_rotation = package_pin.rotation_in_degree;
        if (pin_rotation % 90 == 0)
        {
          int pin_ninety_degree_factor = ((int) pin_rotation) / 90;
          if (pin_ninety_degree_factor != 0)
          {
            curr_shape = (ConvexShape) curr_shape.turn_90_degree(pin_ninety_degree_factor, Point.ZERO);
          }
        }
        else
        {
          curr_shape = (ConvexShape) curr_shape.rotate_approx(Math.toRadians(pin_rotation), FloatPoint.ZERO);
        }

        if (mirror_at_y_axis)
        {
          curr_shape = (ConvexShape) curr_shape.mirror_vertical(Point.ZERO);
        }

        // translate the shape first relative to the component
        ConvexShape translated_shape = (ConvexShape) curr_shape.translate_by(rel_location);

        if (component_rotation % 90 == 0)
        {
          int component_ninety_degree_factor = ((int) component_rotation) / 90;
          if (component_ninety_degree_factor != 0)
          {
            translated_shape = (ConvexShape) translated_shape.turn_90_degree(component_ninety_degree_factor, Point.ZERO);
          }
        }
        else
        {
          translated_shape = (ConvexShape) translated_shape.rotate_approx(Math.toRadians(component_rotation), FloatPoint.ZERO);
        }
        if (!component.placed_on_front() && board.components.get_flip_style_rotate_first())
        {
          translated_shape = (ConvexShape) translated_shape.mirror_vertical(Point.ZERO);
        }
        this.precalculated_shapes[index] = (ConvexShape) translated_shape.translate_by(component_translation);
      }
    }
    return this.precalculated_shapes[p_index];
  }

  /**
   * Returns the layer of the padstack shape corresponding to the shape with index p_index.
   */
  int get_padstack_layer(int p_index)
  {
    Padstack padstack = get_padstack();
    Component component = board.components.get(this.get_component_no());
    int padstack_layer;
    if (component.placed_on_front() || padstack.placed_absolute)
    {
      padstack_layer = p_index + this.first_layer();
    }
    else
    {
      padstack_layer = padstack.board_layer_count() - p_index - this.first_layer() - 1;
    }
    return padstack_layer;
  }

  /**
   * Calculates the allowed trace exit directions of the shape of this padstack on layer p_layer
   * together with the minimal trace line lengths into their directions. Currently implemented
   * only for box shapes, where traces are allowed to exit the pad only on the small sides.
   */
  public Collection<TraceExitRestriction> get_trace_exit_restrictions(int p_layer)
  {
    Collection<TraceExitRestriction> result = new LinkedList<>();
    int padstack_layer = this.get_padstack_layer(p_layer - this.first_layer());
    double pad_xy_factor = 1.5;
    // setting 1.5 to a higher factor may hinder the shove algorithm of the autorouter between
    // the pins of SMD components, because the channels can get blocked by the shove_fixed stubs.

    Component component = board.components.get(this.get_component_no());
    if (component != null)
    {
      if (component
          .get_package()
          .pin_count() <= 3)
      {
        pad_xy_factor *= 2; // allow connection to the longer side also for shorter pads.
      }
    }

    Collection<Direction> padstack_exit_directions = this
        .get_padstack()
        .get_trace_exit_directions(padstack_layer, pad_xy_factor);
    if (padstack_exit_directions.isEmpty())
    {
      return result;
    }

    if (component == null)
    {
      return result;
    }
    Shape curr_shape = this.get_shape(p_layer - this.first_layer());
    if (!(curr_shape instanceof TileShape pad_shape))
    {
      return result;
    }
    double component_rotation = component.get_rotation_in_degree();
    Point pin_center = this.get_center();
    FloatPoint center_approx = pin_center.to_float();

    for (Direction curr_padstack_exit_direction : padstack_exit_directions)
    {

      Package lib_package = component.get_package();
      if (lib_package == null)
      {
        continue;
      }
      Package.Pin package_pin = lib_package.get_pin(this.pin_no);
      if (package_pin == null)
      {
        continue;
      }
      double curr_rotation_in_degree = component_rotation + package_pin.rotation_in_degree;
      Direction curr_exit_direction;
      if (curr_rotation_in_degree % 45 == 0)
      {
        int fortyfive_degree_factor = ((int) curr_rotation_in_degree) / 45;
        curr_exit_direction = curr_padstack_exit_direction.turn_45_degree(fortyfive_degree_factor);
      }
      else
      {
        double curr_angle_in_radian = Math.toRadians(curr_rotation_in_degree) + curr_padstack_exit_direction.angle_approx();
        curr_exit_direction = Direction.get_instance_approx(curr_angle_in_radian);
      }
      // calculate the minimum line length from the pin center into curr_exit_direction
      int intersecting_border_line_no = pad_shape.intersecting_border_line_no(pin_center, curr_exit_direction);
      if (intersecting_border_line_no < 0)
      {
        FRLogger.warn("Pin.get_trace_exit_restrictions: border line not found");
        continue;
      }
      Line curr_exit_line = new Line(pin_center, curr_exit_direction);
      FloatPoint nearest_border_point = curr_exit_line.intersection_approx(pad_shape.border_line(intersecting_border_line_no));
      TraceExitRestriction curr_exit_restriction = new TraceExitRestriction(curr_exit_direction, center_approx.distance(nearest_border_point));
      result.add(curr_exit_restriction);
    }
    return result;
  }

  /**
   * Returns true, if this pin has exit restrictions on some kayer.
   */
  public boolean has_trace_exit_restrictions()
  {
    for (int i = this.first_layer(); i <= this.last_layer(); ++i)
    {
      Collection<TraceExitRestriction> curr_exit_restrictions = get_trace_exit_restrictions(i);
      if (!curr_exit_restrictions.isEmpty())
      {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns true, if vias throw the pads of this pins are allowed, false, otherwise. Currently,
   * drills are allowed to SMD-pins.
   */
  public boolean drill_allowed()
  {
    return (this.first_layer() == this.last_layer());
  }

  @Override
  public boolean is_obstacle(Item p_other)
  {
    if (p_other == this || p_other instanceof ObstacleArea)
    {
      return false;
    }
    if (!p_other.shares_net(this))
    {
      return true;
    }
    if (p_other instanceof Trace)
    {
      return false;
    }
    return !this.drill_allowed() || !(p_other instanceof Via) || !((Via) p_other).attach_allowed;
  }

  @Override
  public void turn_90_degree(int p_factor, IntPoint p_pole)
  {
    this.set_center(null);
    clear_derived_data();
  }

  @Override
  public void rotate_approx(double p_angle_in_degree, FloatPoint p_pole)
  {
    this.set_center(null);
    this.clear_derived_data();
  }

  @Override
  public void change_placement_side(IntPoint p_pole)
  {
    this.set_center(null);
    this.clear_derived_data();
  }

  @Override
  public void clear_derived_data()
  {
    super.clear_derived_data();
    this.precalculated_shapes = null;
  }

  /**
   * Return all Pins, that can be swapped with this pin.
   */
  public Set<Pin> get_swappable_pins()
  {
    Set<Pin> result = new TreeSet<>();
    Component component = this.board.components.get(this.get_component_no());
    if (component == null)
    {
      return result;
    }
    LogicalPart logical_part = component.get_logical_part();
    if (logical_part == null)
    {
      return result;
    }
    LogicalPart.PartPin this_part_pin = logical_part.get_pin(this.pin_no);
    if (this_part_pin == null)
    {
      return result;
    }
    if (this_part_pin.gate_pin_swap_code <= 0)
    {
      return result;
    }
    // look up all part pins with the same gate_name and the same gate_pin_swap_code
    for (int i = 0; i < logical_part.pin_count(); ++i)
    {
      if (i == this.pin_no)
      {
        continue;
      }
      LogicalPart.PartPin curr_part_pin = logical_part.get_pin(i);
      if (curr_part_pin != null && curr_part_pin.gate_pin_swap_code == this_part_pin.gate_pin_swap_code && curr_part_pin.gate_name.equals(this_part_pin.gate_name))
      {
        Pin curr_swappeble_pin = this.board.get_pin(this.get_component_no(), curr_part_pin.pin_no);
        if (curr_swappeble_pin != null)
        {
          result.add(curr_swappeble_pin);
        }
        else
        {
          FRLogger.warn("Pin.get_swappable_pins: swappable pin not found");
        }
      }
    }
    return result;
  }

  @Override
  public boolean is_selected_by_filter(ItemSelectionFilter p_filter)
  {
    if (!this.is_selected_by_fixed_filter(p_filter))
    {
      return false;
    }
    return p_filter.is_selected(ItemSelectionFilter.SelectableChoices.PINS);
  }

  @Override
  public Color[] get_draw_colors(GraphicsContext p_graphics_context)
  {
    Color[] result;
    if (this.net_count() > 0)
    {
      result = p_graphics_context.get_pin_colors();
    }
    else
    {
      // display unconnected pins as obstacles
      result = p_graphics_context.get_obstacle_colors();
    }
    return result;
  }

  @Override
  public double get_draw_intensity(GraphicsContext p_graphics_context)
  {
    return p_graphics_context.get_pin_color_intensity();
  }

  /**
   * Swaps the nets of this pin and p_other. Returns false on error.
   */
  public boolean swap(Pin p_other)
  {
    if (this.net_count() > 1 || p_other.net_count() > 1)
    {
      FRLogger.warn("Pin.swap not yet implemented for pins belonging to more than 1 net ");
      return false;
    }
    int this_net_no;
    if (this.net_count() > 0)
    {
      this_net_no = this.get_net_no(0);
    }
    else
    {
      this_net_no = 0;
    }
    int other_net_no;
    if (p_other.net_count() > 0)
    {
      other_net_no = p_other.get_net_no(0);
    }
    else
    {
      other_net_no = 0;
    }
    this.assign_net_no(other_net_no);
    p_other.assign_net_no(this_net_no);
    Pin tmp = this.changed_to;
    this.changed_to = p_other.changed_to;
    p_other.changed_to = tmp;
    return true;
  }

  /**
   * Returns the pin, this pin was changed to by pin swapping, or this pin, if it was not swapped.
   */
  public Pin get_changed_to()
  {
    return changed_to;
  }

  @Override
  public boolean write(ObjectOutputStream p_stream)
  {
    try
    {
      p_stream.writeObject(this);
    } catch (IOException e)
    {
      return false;
    }
    return true;
  }

  /**
   * False, if this drillitem is places on the back side of the board
   */
  @Override
  public boolean is_placed_on_front()
  {
    boolean result = true;
    Component component = board.components.get(this.get_component_no());
    if (component != null)
    {
      result = component.placed_on_front();
    }
    return result;
  }

  /**
   * Returns the smallest width of the pin shape on layer p_layer.
   */
  public double get_min_width(int p_layer)
  {
    int padstack_layer = get_padstack_layer(p_layer - this.first_layer());
    Shape padstack_shape = this
        .get_padstack()
        .get_shape(padstack_layer);
    if (padstack_shape == null)
    {
      FRLogger.warn("Pin.get_min_width: padstack_shape is null");
      return 0;
    }
    IntBox padstack_bounding_box = padstack_shape.bounding_box();
    if (padstack_bounding_box == null)
    {
      FRLogger.warn("Pin.get_min_width: padstack_bounding_box is null");
      return 0;
    }
    return padstack_bounding_box.min_width();
  }

  /**
   * Returns the neckdown half width for traces on p_layer. The neckdown width is used, when the pin
   * width is smaller than the trace width to enter or leave the pin with a trace.
   */
  public int get_trace_neckdown_halfwidth(int p_layer)
  {
    double result = Math.max(0.5 * this.get_min_width(p_layer) - 1, 1);
    return (int) result;
  }

  /**
   * Returns the largest width of the pin shape on layer p_layer.
   */
  public double get_max_width(int p_layer)
  {
    int padstack_layer = get_padstack_layer(p_layer - this.first_layer());
    Shape padstack_shape = this
        .get_padstack()
        .get_shape(padstack_layer);
    if (padstack_shape == null)
    {
      FRLogger.warn("Pin.get_max_width: padstack_shape is null");
      return 0;
    }
    IntBox padstack_bounding_box = padstack_shape.bounding_box();
    if (padstack_bounding_box == null)
    {
      FRLogger.warn("Pin.get_max_width: padstack_bounding_box is null");
      return 0;
    }
    return padstack_bounding_box.max_width();
  }

  @Override
  public void print_info(ObjectInfoPanel p_window, Locale p_locale)
  {
    TextManager tm = new TextManager(this.getClass(), p_locale);

    p_window.append_bold(tm.getText("pin") + ": ");
    p_window.append(tm.getText("component_2") + " ");
    Component component = board.components.get(this.get_component_no());
    p_window.append(component.name, tm.getText("component_info"), component);
    p_window.append(", " + tm.getText("pin_2") + " ");
    p_window.append(component
        .get_package()
        .get_pin(this.pin_no).name);
    p_window.append(", " + tm.getText("padstack") + " ");
    Padstack padstack = this.get_padstack();
    p_window.append(padstack.name, tm.getText("padstack_info"), padstack);
    p_window.append(" " + tm.getText("at") + " ");
    p_window.append(this
        .get_center()
        .to_float());
    this.print_connectable_item_info(p_window, p_locale);
    p_window.newline();
  }

  @Override
  public String get_hover_info(Locale p_locale)
  {
    TextManager tm = new TextManager(this.getClass(), p_locale);

    Component component = board.components.get(this.get_component_no());
    Padstack padstack = this.get_padstack();
    String hover_info = tm.getText("pin") + " : " + tm.getText("component_2") + " " + component.name + " " + tm.getText("pin_2") + " " + component
        .get_package()
        .get_pin(this.pin_no).name + " " + tm.getText("padstack") + " " + padstack.name + " " + this.get_connectable_item_hover_info(p_locale);
    return hover_info;
  }

  /**
   * Calculates the nearest exit restriction direction for changing p_trace_polyline.
   * p_trace_polyline is assumed to start at the pin center. Returns null, if there is no matching
   * exit restrictions.
   */
  Direction calc_nearest_exit_restriction_direction(Polyline p_trace_polyline, int p_trace_half_width, int p_layer)
  {
    Collection<Pin.TraceExitRestriction> trace_exit_restrictions = this.get_trace_exit_restrictions(p_layer);
    if (trace_exit_restrictions.isEmpty())
    {
      return null;
    }
    Shape pin_shape = this.get_shape(p_layer - this.first_layer());
    Point pin_center = this.get_center();
    if (!(pin_shape instanceof TileShape))
    {
      return null;
    }
    final double edge_to_turn_dist = this.board.rules.get_pin_edge_to_turn_dist();
    if (edge_to_turn_dist < 0)
    {
      return null;
    }
    TileShape offset_pin_shape = (TileShape) ((TileShape) pin_shape).offset(edge_to_turn_dist + p_trace_half_width);
    int[][] entries = offset_pin_shape.entrance_points(p_trace_polyline);
    if (entries.length == 0)
    {
      return null;
    }
    int[] latest_entry_tuple = entries[entries.length - 1];
    FloatPoint trace_entry_location_approx = p_trace_polyline.arr[latest_entry_tuple[0]].intersection_approx(offset_pin_shape.border_line(latest_entry_tuple[1]));
    // calculate the nearest legal pin exit point to trace_entry_location_approx
    double min_exit_corner_distance = Double.MAX_VALUE;
    FloatPoint nearest_exit_corner = null;
    Direction pin_exit_direction = null;
    final double TOLERANCE = 1;
    for (Pin.TraceExitRestriction curr_exit_restriction : trace_exit_restrictions)
    {
      int curr_intersecting_border_line_no = offset_pin_shape.intersecting_border_line_no(pin_center, curr_exit_restriction.direction);
      Line curr_pin_exit_ray = new Line(pin_center, curr_exit_restriction.direction);
      FloatPoint curr_exit_corner = curr_pin_exit_ray.intersection_approx(offset_pin_shape.border_line(curr_intersecting_border_line_no));
      double curr_exit_corner_distance = curr_exit_corner.distance_square(trace_entry_location_approx);
      boolean new_nearest_corner_found = false;
      if (curr_exit_corner_distance + TOLERANCE < min_exit_corner_distance)
      {
        new_nearest_corner_found = true;
      }
      else if (curr_exit_corner_distance < min_exit_corner_distance + TOLERANCE)
      {
        // the distances are near equal, compare to the previous corners of p_trace_polyline
        for (int i = 1; i < p_trace_polyline.corner_count(); ++i)
        {
          FloatPoint curr_trace_corner = p_trace_polyline.corner_approx(i);
          double curr_trace_corner_distance = curr_trace_corner.distance_square(curr_exit_corner);
          double old_trace_corner_distance = curr_trace_corner.distance_square(nearest_exit_corner);
          if (curr_trace_corner_distance + TOLERANCE < old_trace_corner_distance)
          {
            new_nearest_corner_found = true;
            break;
          }
          else if (curr_trace_corner_distance > old_trace_corner_distance + TOLERANCE)
          {
            break;
          }
        }
      }
      if (new_nearest_corner_found)
      {
        min_exit_corner_distance = curr_exit_corner_distance;
        pin_exit_direction = curr_exit_restriction.direction;
        nearest_exit_corner = curr_exit_corner;
      }
    }
    return pin_exit_direction;
  }

  /**
   * Calculates the nearest trace exit point of the pin on p_layer. Returns null, if the pin has no
   * trace exit restrictions.
   */
  public FloatPoint nearest_trace_exit_corner(FloatPoint p_from_point, int p_trace_half_width, int p_layer)
  {
    Collection<Pin.TraceExitRestriction> trace_exit_restrictions = this.get_trace_exit_restrictions(p_layer);
    if (trace_exit_restrictions.isEmpty())
    {
      return null;
    }
    Shape pin_shape = this.get_shape(p_layer - this.first_layer());
    Point pin_center = this.get_center();
    if (!(pin_shape instanceof TileShape))
    {
      return null;
    }
    final double edge_to_turn_dist = this.board.rules.get_pin_edge_to_turn_dist();
    if (edge_to_turn_dist < 0)
    {
      return null;
    }
    TileShape offset_pin_shape = (TileShape) ((TileShape) pin_shape).offset(edge_to_turn_dist + p_trace_half_width);

    // calculate the nearest legal pin exit point to trace_entry_location_approx
    double min_exit_corner_distance = Double.MAX_VALUE;
    FloatPoint nearest_exit_corner = null;
    for (Pin.TraceExitRestriction curr_exit_restriction : trace_exit_restrictions)
    {
      int curr_intersecting_border_line_no = offset_pin_shape.intersecting_border_line_no(pin_center, curr_exit_restriction.direction);
      Line curr_pin_exit_ray = new Line(pin_center, curr_exit_restriction.direction);
      FloatPoint curr_exit_corner = curr_pin_exit_ray.intersection_approx(offset_pin_shape.border_line(curr_intersecting_border_line_no));
      double curr_exit_corner_distance = curr_exit_corner.distance_square(p_from_point);
      if (curr_exit_corner_distance < min_exit_corner_distance)
      {
        min_exit_corner_distance = curr_exit_corner_distance;
        nearest_exit_corner = curr_exit_corner;
      }
    }
    return nearest_exit_corner;
  }

  @Override
  public String toString()
  {
    StringBuilder simpleName = new StringBuilder();

    simpleName.append(this
        .getClass()
        .getSimpleName()
        .toLowerCase());

    if (pin_no > 0)
    {
      simpleName.append(" #");
      simpleName.append(pin_no);
    }

    if (component_no > 0)
    {
      simpleName.append(" of component #");
      simpleName.append(component_no);
    }

    return simpleName.toString();
  }

  /**
   * Describes an exit restriction from a trace from a pin pad.
   */
  public static class TraceExitRestriction
  {

    public final Direction direction;
    public final double min_length;

    /**
     * Creates a new instance of TraceExitRestriction
     */
    public TraceExitRestriction(Direction p_direction, double p_min_length)
    {
      direction = p_direction;
      min_length = p_min_length;
    }
  }
}