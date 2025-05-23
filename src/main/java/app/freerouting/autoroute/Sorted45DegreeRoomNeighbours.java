package app.freerouting.autoroute;

import app.freerouting.board.Item;
import app.freerouting.board.SearchTreeObject;
import app.freerouting.board.ShapeSearchTree;
import app.freerouting.datastructures.ShapeTree;
import app.freerouting.geometry.planar.*;
import app.freerouting.logger.FRLogger;

import java.util.Collection;
import java.util.LinkedList;
import java.util.SortedSet;
import java.util.TreeSet;

public class Sorted45DegreeRoomNeighbours
{

  public final CompleteExpansionRoom completed_room;
  public final SortedSet<SortedRoomNeighbour> sorted_neighbours;
  private final ExpansionRoom from_room;
  private final IntOctagon room_shape;
  private final boolean[] edge_interior_touches_obstacle;

  /**
   * Creates a new instance of Sorted45DegreeRoomNeighbours
   */
  private Sorted45DegreeRoomNeighbours(ExpansionRoom p_from_room, CompleteExpansionRoom p_completed_room)
  {
    from_room = p_from_room;
    completed_room = p_completed_room;
    room_shape = p_completed_room
        .get_shape()
        .bounding_octagon();
    sorted_neighbours = new TreeSet<>();

    edge_interior_touches_obstacle = new boolean[8];
    for (int i = 0; i < 8; ++i)
    {
      edge_interior_touches_obstacle[i] = false;
    }
  }

  public static CompleteExpansionRoom calculate(ExpansionRoom p_room, AutorouteEngine p_autoroute_engine)
  {
    int net_no = p_autoroute_engine.get_net_no();
    Sorted45DegreeRoomNeighbours room_neighbours = Sorted45DegreeRoomNeighbours.calculate_neighbours(p_room, net_no, p_autoroute_engine.autoroute_search_tree, p_autoroute_engine.generate_room_id_no());
    if (room_neighbours == null)
    {
      return null;
    }

    // Check, that each side of the room shape has at least one touching neighbour.
    // Otherwise, improve the room shape by enlarging.
    boolean edge_removed = room_neighbours.try_remove_edge_line(net_no, p_autoroute_engine.autoroute_search_tree);
    CompleteExpansionRoom result = room_neighbours.completed_room;
    if (edge_removed)
    {
      p_autoroute_engine.remove_all_doors(result);
      return calculate(p_room, p_autoroute_engine);
    }

    // Now calculate the new incomplete rooms together with the doors
    // between this room and the sorted neighbours.

    if (room_neighbours.sorted_neighbours.isEmpty())
    {
      if (result instanceof ObstacleExpansionRoom)
      {
        room_neighbours.calculate_edge_incomplete_rooms_of_obstacle_expansion_room(0, 7, p_autoroute_engine);
      }
    }
    else
    {
      room_neighbours.calculate_new_incomplete_rooms(p_autoroute_engine);
    }
    return result;
  }

  /**
   * Calculates all touching neighbours of p_room and sorts them in counterclock sense around the
   * boundary of the room shape.
   */
  private static Sorted45DegreeRoomNeighbours calculate_neighbours(ExpansionRoom p_room, int p_net_no, ShapeSearchTree p_autoroute_search_tree, int p_room_id_no)
  {
    TileShape room_shape = p_room.get_shape();
    CompleteExpansionRoom completed_room;
    if (p_room instanceof IncompleteFreeSpaceExpansionRoom)
    {
      completed_room = new CompleteFreeSpaceExpansionRoom(room_shape, p_room.get_layer(), p_room_id_no);
    }
    else if (p_room instanceof ObstacleExpansionRoom)
    {
      completed_room = (ObstacleExpansionRoom) p_room;
    }
    else
    {
      FRLogger.warn("Sorted45DegreeRoomNeighbours.calculate_neighbours: unexpected expansion room type");
      return null;
    }
    IntOctagon room_oct = room_shape.bounding_octagon();
    Sorted45DegreeRoomNeighbours result = new Sorted45DegreeRoomNeighbours(p_room, completed_room);
    Collection<ShapeTree.TreeEntry> overlapping_objects = new LinkedList<>();
    p_autoroute_search_tree.overlapping_tree_entries(room_shape, p_room.get_layer(), overlapping_objects);
    // Calculate the touching neighbour objects and sort them in counterclock sense
    // around the border of the room shape.
    for (ShapeTree.TreeEntry curr_entry : overlapping_objects)
    {
      SearchTreeObject curr_object = (SearchTreeObject) curr_entry.object;
      if (curr_object == p_room)
      {
        continue;
      }
      if ((completed_room instanceof CompleteFreeSpaceExpansionRoom) && !curr_object.is_trace_obstacle(p_net_no))
      {
        ((CompleteFreeSpaceExpansionRoom) completed_room).calculate_target_doors(curr_entry, p_net_no, p_autoroute_search_tree);
        continue;
      }
      TileShape curr_shape = curr_object.get_tree_shape(p_autoroute_search_tree, curr_entry.shape_index_in_object);
      IntOctagon curr_oct = curr_shape.bounding_octagon();
      IntOctagon intersection = room_oct.intersection(curr_oct);
      int dimension = intersection.dimension();
      if (dimension > 1 && completed_room instanceof ObstacleExpansionRoom)
      {
        if (curr_object instanceof Item curr_item)
        {
          // only Obstacle expansion room may have a 2-dim overlap
          if (curr_item.is_routable())
          {
            ItemAutorouteInfo item_info = curr_item.get_autoroute_info();
            ObstacleExpansionRoom curr_overlap_room = item_info.get_expansion_room(curr_entry.shape_index_in_object, p_autoroute_search_tree);
            ((ObstacleExpansionRoom) completed_room).create_overlap_door(curr_overlap_room);
          }
        }
        continue;
      }
      if (dimension < 0)
      {
        // may happen at a corner from 2 diagonal lines with non integer  coordinates (--.5, ---.5).
        continue;
      }
      result.add_sorted_neighbour(curr_oct, intersection);
      if (dimension > 0)
      {
        // make  sure, that there is a door to the neighbour room.
        ExpansionRoom neighbour_room = null;
        if (curr_object instanceof ExpansionRoom)
        {
          neighbour_room = (ExpansionRoom) curr_object;
        }
        else if (curr_object instanceof Item curr_item)
        {
          if (curr_item.is_routable())
          {
            // expand the item for ripup and pushing purposes
            ItemAutorouteInfo item_info = curr_item.get_autoroute_info();
            neighbour_room = item_info.get_expansion_room(curr_entry.shape_index_in_object, p_autoroute_search_tree);
          }
        }
        if (neighbour_room != null)
        {
          if (SortedRoomNeighbours.insert_door_ok(completed_room, neighbour_room, intersection))
          {
            ExpansionDoor new_door = new ExpansionDoor(completed_room, neighbour_room);
            neighbour_room.add_door(new_door);
            completed_room.add_door(new_door);
          }
        }
      }
    }
    return result;
  }

  private static IntOctagon remove_not_touching_border_lines(IntOctagon p_room_oct, boolean[] p_edge_interior_touches_obstacle)
  {
    int lx;
    if (p_edge_interior_touches_obstacle[6])
    {
      lx = p_room_oct.leftX;
    }
    else
    {
      lx = -Limits.CRIT_INT;
    }

    int ly;
    if (p_edge_interior_touches_obstacle[0])
    {
      ly = p_room_oct.bottomY;
    }
    else
    {
      ly = -Limits.CRIT_INT;
    }

    int rx;
    if (p_edge_interior_touches_obstacle[2])
    {
      rx = p_room_oct.rightX;
    }
    else
    {
      rx = Limits.CRIT_INT;
    }

    int uy;
    if (p_edge_interior_touches_obstacle[4])
    {
      uy = p_room_oct.topY;
    }
    else
    {
      uy = Limits.CRIT_INT;
    }

    int ulx;
    if (p_edge_interior_touches_obstacle[5])
    {
      ulx = p_room_oct.upperLeftDiagonalX;
    }
    else
    {
      ulx = -Limits.CRIT_INT;
    }

    int lrx;
    if (p_edge_interior_touches_obstacle[1])
    {
      lrx = p_room_oct.lowerRightDiagonalX;
    }
    else
    {
      lrx = Limits.CRIT_INT;
    }

    int llx;
    if (p_edge_interior_touches_obstacle[7])
    {
      llx = p_room_oct.lowerLeftDiagonalX;
    }
    else
    {
      llx = -Limits.CRIT_INT;
    }

    int urx;
    if (p_edge_interior_touches_obstacle[3])
    {
      urx = p_room_oct.upperRightDiagonalX;
    }
    else
    {
      urx = Limits.CRIT_INT;
    }

    IntOctagon result = new IntOctagon(lx, ly, rx, uy, ulx, lrx, llx, urx);
    return result.normalize();
  }

  private void add_sorted_neighbour(IntOctagon p_neighbour_shape, IntOctagon p_intersection)
  {
    SortedRoomNeighbour new_neighbour = new SortedRoomNeighbour(p_neighbour_shape, p_intersection);
    if (new_neighbour.last_touching_side >= 0)
    {
      sorted_neighbours.add(new_neighbour);
    }
  }

  /**
   * Calculates an incomplete room for each edge side from p_from_side_no to p_to_side_no.
   */
  private void calculate_edge_incomplete_rooms_of_obstacle_expansion_room(int p_from_side_no, int p_to_side_no, AutorouteEngine p_autoroute_engine)
  {
    if (!(this.from_room instanceof ObstacleExpansionRoom))
    {
      FRLogger.warn("Sorted45DegreeRoomNeighbours.calculate_side_incomplete_rooms_of_obstacle_expansion_room: ObstacleExpansionRoom expected for this.from_room");
      return;
    }
    IntOctagon board_bounding_oct = p_autoroute_engine.board
        .get_bounding_box()
        .bounding_octagon();
    IntPoint curr_corner = this.room_shape.corner(p_from_side_no);
    int curr_side_no = p_from_side_no;
    for (; ; )
    {
      int next_side_no = (curr_side_no + 1) % 8;
      IntPoint next_corner = this.room_shape.corner(next_side_no);
      if (!curr_corner.equals(next_corner))
      {
        int lx = board_bounding_oct.leftX;
        int ly = board_bounding_oct.bottomY;
        int rx = board_bounding_oct.rightX;
        int uy = board_bounding_oct.topY;
        int ulx = board_bounding_oct.upperLeftDiagonalX;
        int lrx = board_bounding_oct.lowerRightDiagonalX;
        int llx = board_bounding_oct.lowerLeftDiagonalX;
        int urx = board_bounding_oct.upperRightDiagonalX;
        switch (curr_side_no)
        {
          case 0 -> uy = this.room_shape.bottomY;
          case 1 -> ulx = this.room_shape.lowerRightDiagonalX;
          case 2 -> lx = this.room_shape.rightX;
          case 3 -> llx = this.room_shape.upperRightDiagonalX;
          case 4 -> ly = this.room_shape.topY;
          case 5 -> lrx = this.room_shape.upperLeftDiagonalX;
          case 6 -> rx = this.room_shape.leftX;
          case 7 -> urx = this.room_shape.lowerLeftDiagonalX;
          default ->
          {
            FRLogger.warn("SortedOrthoganelRoomNeighbours.calculate_edge_incomplete_rooms_of_obstacle_expansion_room: curr_side_no illegal");
            return;
          }
        }
        insert_incomplete_room(p_autoroute_engine, lx, ly, rx, uy, ulx, lrx, llx, urx);
      }
      if (curr_side_no == p_to_side_no)
      {
        break;
      }
      curr_side_no = next_side_no;
    }
  }

  /**
   * Check, that each side of the room shape has at least one touching neighbour. Otherwise, the room
   * shape will be improved the by enlarging. Returns true, if the room shape was changed.
   */
  private boolean try_remove_edge_line(int p_net_no, ShapeSearchTree p_autoroute_search_tree)
  {
    if (!(this.from_room instanceof IncompleteFreeSpaceExpansionRoom curr_incomplete_room))
    {
      return false;
    }
    if (!(curr_incomplete_room.get_shape() instanceof IntOctagon room_oct))
    {
      FRLogger.warn("Sorted45DegreeRoomNeighbours.try_remove_edge_line: IntOctagon expected for room_shape type");
      return false;
    }
    double room_area = room_oct.area();

    boolean try_remove_edge_lines = false;
    for (int i = 0; i < 8; ++i)
    {
      if (!this.edge_interior_touches_obstacle[i])
      {
        FloatPoint prev_corner = this.room_shape.corner_approx(i);
        FloatPoint next_corner = this.room_shape.corner_approx(this.room_shape.next_no(i));
        if (prev_corner.distance_square(next_corner) > 1)
        {
          try_remove_edge_lines = true;
          break;
        }
      }
    }

    if (try_remove_edge_lines)
    {
      // Touching neighbour missing at the edge side with index remove_edge_no
      // Remove the edge line and restart the algorithm.

      IntOctagon enlarged_oct = remove_not_touching_border_lines(room_oct, this.edge_interior_touches_obstacle);

      Collection<ExpansionDoor> door_list = this.completed_room.get_doors();
      TileShape ignore_shape = null;
      SearchTreeObject ignore_object = null;
      double max_door_area = 0;
      for (ExpansionDoor curr_door : door_list)
      {
        // insert the overlapping doors with CompleteFreeSpaceExpansionRooms
        // for the information in complete_shape about the objects to ignore.
        if (curr_door.dimension == 2)
        {
          CompleteExpansionRoom other_room = curr_door.other_room(this.completed_room);
          {
            if (other_room instanceof CompleteFreeSpaceExpansionRoom)
            {
              TileShape curr_door_shape = curr_door.get_shape();
              double curr_door_area = curr_door_shape.area();
              if (curr_door_area > max_door_area)
              {
                max_door_area = curr_door_area;
                ignore_shape = curr_door_shape;
                ignore_object = (CompleteFreeSpaceExpansionRoom) other_room;
              }
            }
          }
        }
      }
      IncompleteFreeSpaceExpansionRoom enlarged_room = new IncompleteFreeSpaceExpansionRoom(enlarged_oct, curr_incomplete_room.get_layer(), curr_incomplete_room.get_contained_shape());
      Collection<IncompleteFreeSpaceExpansionRoom> new_rooms = p_autoroute_search_tree.complete_shape(enlarged_room, p_net_no, ignore_object, ignore_shape);
      if (new_rooms.size() == 1)
      {
        // Check, that the area increases to prevent endless loop.
        IncompleteFreeSpaceExpansionRoom new_room = new_rooms
            .iterator()
            .next();
        if (new_room
            .get_shape()
            .area() > room_area)
        {
          curr_incomplete_room.set_shape(new_room.get_shape());
          curr_incomplete_room.set_contained_shape(new_room.get_contained_shape());
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Inserts a new incomplete room with an octagon shape.
   */
  private void insert_incomplete_room(AutorouteEngine p_autoroute_engine, int p_lx, int p_ly, int p_rx, int p_uy, int p_ulx, int p_lrx, int p_llx, int p_urx)
  {
    IntOctagon new_incomplete_room_shape = new IntOctagon(p_lx, p_ly, p_rx, p_uy, p_ulx, p_lrx, p_llx, p_urx);
    new_incomplete_room_shape = new_incomplete_room_shape.normalize();
    if (new_incomplete_room_shape.dimension() == 2)
    {
      IntOctagon new_contained_shape = this.room_shape.intersection(new_incomplete_room_shape);
      if (!new_contained_shape.is_empty())
      {
        int door_dimension = new_contained_shape.dimension();
        if (door_dimension > 0)
        {
          FreeSpaceExpansionRoom new_room = p_autoroute_engine.add_incomplete_expansion_room(new_incomplete_room_shape, this.from_room.get_layer(), new_contained_shape);
          ExpansionDoor new_door = new ExpansionDoor(this.completed_room, new_room, door_dimension);
          this.completed_room.add_door(new_door);
          new_room.add_door(new_door);
        }
      }
    }
  }

  private void calculate_new_incomplete_rooms_for_obstacle_expansion_room(SortedRoomNeighbour p_prev_neighbour, SortedRoomNeighbour p_next_neighbour, AutorouteEngine p_autoroute_engine)
  {
    int from_side_no = p_prev_neighbour.last_touching_side;
    int to_side_no = p_next_neighbour.first_touching_side;
    if (from_side_no == to_side_no && p_prev_neighbour != p_next_neighbour)
    {
      // no return in case of only 1 neighbour.
      return;
    }
    IntOctagon board_bounding_oct = p_autoroute_engine.board.bounding_box.bounding_octagon();

    // insert the new incomplete room from p_prev_neighbour to the next corner of the room shape.

    int lx = board_bounding_oct.leftX;
    int ly = board_bounding_oct.bottomY;
    int rx = board_bounding_oct.rightX;
    int uy = board_bounding_oct.topY;
    int ulx = board_bounding_oct.upperLeftDiagonalX;
    int lrx = board_bounding_oct.lowerRightDiagonalX;
    int llx = board_bounding_oct.lowerLeftDiagonalX;
    int urx = board_bounding_oct.upperRightDiagonalX;
    switch (from_side_no)
    {
      case 0 ->
      {
        uy = this.room_shape.bottomY;
        ulx = p_prev_neighbour.intersection.lowerRightDiagonalX;
      }
      case 1 ->
      {
        ulx = this.room_shape.lowerRightDiagonalX;
        lx = p_prev_neighbour.intersection.rightX;
      }
      case 2 ->
      {
        lx = this.room_shape.rightX;
        llx = p_prev_neighbour.intersection.upperRightDiagonalX;
      }
      case 3 ->
      {
        llx = this.room_shape.upperRightDiagonalX;
        ly = p_prev_neighbour.intersection.topY;
      }
      case 4 ->
      {
        ly = this.room_shape.topY;
        lrx = p_prev_neighbour.intersection.upperLeftDiagonalX;
      }
      case 5 ->
      {
        lrx = this.room_shape.upperLeftDiagonalX;
        rx = p_prev_neighbour.intersection.leftX;
      }
      case 6 ->
      {
        rx = this.room_shape.leftX;
        urx = p_prev_neighbour.intersection.lowerLeftDiagonalX;
      }
      case 7 ->
      {
        urx = this.room_shape.lowerLeftDiagonalX;
        uy = p_prev_neighbour.intersection.bottomY;
      }
    }
    insert_incomplete_room(p_autoroute_engine, lx, ly, rx, uy, ulx, lrx, llx, urx);

    // insert the new incomplete room from p_prev_neighbour to the next corner of the room shape.

    lx = board_bounding_oct.leftX;
    ly = board_bounding_oct.bottomY;
    rx = board_bounding_oct.rightX;
    uy = board_bounding_oct.topY;
    ulx = board_bounding_oct.upperLeftDiagonalX;
    lrx = board_bounding_oct.lowerRightDiagonalX;
    llx = board_bounding_oct.lowerLeftDiagonalX;
    urx = board_bounding_oct.upperRightDiagonalX;

    switch (to_side_no)
    {
      case 0 ->
      {
        uy = this.room_shape.bottomY;
        urx = p_next_neighbour.intersection.lowerLeftDiagonalX;
      }
      case 1 ->
      {
        ulx = this.room_shape.lowerRightDiagonalX;
        uy = p_next_neighbour.intersection.bottomY;
      }
      case 2 ->
      {
        lx = this.room_shape.rightX;
        ulx = p_next_neighbour.intersection.lowerRightDiagonalX;
      }
      case 3 ->
      {
        llx = this.room_shape.upperRightDiagonalX;
        lx = p_next_neighbour.intersection.rightX;
      }
      case 4 ->
      {
        ly = this.room_shape.topY;
        llx = p_next_neighbour.intersection.upperRightDiagonalX;
      }
      case 5 ->
      {
        lrx = this.room_shape.upperLeftDiagonalX;
        ly = p_next_neighbour.intersection.topY;
      }
      case 6 ->
      {
        rx = this.room_shape.leftX;
        lrx = p_next_neighbour.intersection.upperLeftDiagonalX;
      }
      case 7 ->
      {
        urx = this.room_shape.lowerLeftDiagonalX;
        rx = p_next_neighbour.intersection.leftX;
      }
    }
    insert_incomplete_room(p_autoroute_engine, lx, ly, rx, uy, ulx, lrx, llx, urx);

    // Insert the new incomplete rooms on the intermediate free sides of the obstacle expansion
    // room.
    int curr_from_side_no = (from_side_no + 1) % 8;
    if (curr_from_side_no == to_side_no)
    {
      return;
    }
    int curr_to_side_no = (to_side_no + 7) % 8;
    this.calculate_edge_incomplete_rooms_of_obstacle_expansion_room(curr_from_side_no, curr_to_side_no, p_autoroute_engine);
  }

  private void calculate_new_incomplete_rooms(AutorouteEngine p_autoroute_engine)
  {
    IntOctagon board_bounding_oct = p_autoroute_engine.board.bounding_box.bounding_octagon();
    SortedRoomNeighbour prev_neighbour = this.sorted_neighbours.last();
    if (this.from_room instanceof ObstacleExpansionRoom && this.sorted_neighbours.size() == 1)
    {
      // ObstacleExpansionRoom has only 1 neighbour
      calculate_new_incomplete_rooms_for_obstacle_expansion_room(prev_neighbour, prev_neighbour, p_autoroute_engine);
      return;
    }

    for (SortedRoomNeighbour next_neighbour : this.sorted_neighbours)
    {
      boolean insert_incomplete_room;

      if (this.completed_room instanceof ObstacleExpansionRoom && this.sorted_neighbours.size() == 2)
      {
        // check, if this site is touching or open.
        TileShape intersection = next_neighbour.intersection.intersection(prev_neighbour.intersection);
        if (intersection.is_empty())
        {
          insert_incomplete_room = true;
        }
        else if (intersection.dimension() >= 1)
        {
          insert_incomplete_room = false;
        }
        else // dimension = 1
        {
          // touch at a corner of the room shape
          if (prev_neighbour.last_touching_side == next_neighbour.first_touching_side)
          {
            // touch along the side of the room shape
            insert_incomplete_room = false;
          }
          else
          {
            insert_incomplete_room = prev_neighbour.last_touching_side != (next_neighbour.first_touching_side + 1) % 8;
          }
        }
      }
      else
      {
        // the 2 neighbours do not touch
        insert_incomplete_room = !next_neighbour.intersection.intersects(prev_neighbour.intersection);
      }

      if (insert_incomplete_room)
      {
        // create a door to a new incomplete expansion room between
        // the last corner of the previous neighbour and the first corner of the
        // current neighbour

        if (this.from_room instanceof ObstacleExpansionRoom && next_neighbour.first_touching_side != prev_neighbour.last_touching_side)
        {
          calculate_new_incomplete_rooms_for_obstacle_expansion_room(prev_neighbour, next_neighbour, p_autoroute_engine);
        }
        else
        {
          int lx = board_bounding_oct.leftX;
          int ly = board_bounding_oct.bottomY;
          int rx = board_bounding_oct.rightX;
          int uy = board_bounding_oct.topY;
          int ulx = board_bounding_oct.upperLeftDiagonalX;
          int lrx = board_bounding_oct.lowerRightDiagonalX;
          int llx = board_bounding_oct.lowerLeftDiagonalX;
          int urx = board_bounding_oct.upperRightDiagonalX;

          switch (next_neighbour.first_touching_side)
          {
            case 0 ->
            {
              if (prev_neighbour.intersection.lowerLeftDiagonalX < next_neighbour.intersection.lowerLeftDiagonalX)
              {
                urx = next_neighbour.intersection.lowerLeftDiagonalX;
                uy = prev_neighbour.intersection.bottomY;
                if (prev_neighbour.last_touching_side == 0)
                {
                  ulx = prev_neighbour.intersection.lowerRightDiagonalX;
                }
              }
              else if (prev_neighbour.intersection.lowerLeftDiagonalX > next_neighbour.intersection.lowerLeftDiagonalX)
              {
                rx = next_neighbour.intersection.leftX;
                urx = prev_neighbour.intersection.lowerLeftDiagonalX;
              }
              else // prev_neighbour.intersection.llx == next_neighbour.intersection.llx
              {
                urx = next_neighbour.intersection.lowerLeftDiagonalX;
              }
            }
            case 1 ->
            {
              if (prev_neighbour.intersection.bottomY < next_neighbour.intersection.bottomY)
              {
                uy = next_neighbour.intersection.bottomY;
                ulx = prev_neighbour.intersection.lowerRightDiagonalX;
                if (prev_neighbour.last_touching_side == 1)
                {
                  lx = prev_neighbour.intersection.rightX;
                }
              }
              else if (prev_neighbour.intersection.bottomY > next_neighbour.intersection.bottomY)
              {
                uy = prev_neighbour.intersection.bottomY;
                urx = next_neighbour.intersection.lowerLeftDiagonalX;
              }
              else // prev_neighbour.intersection.ly == next_neighbour.intersection.ly
              {
                uy = next_neighbour.intersection.bottomY;
              }
            }
            case 2 ->
            {
              if (prev_neighbour.intersection.lowerRightDiagonalX > next_neighbour.intersection.lowerRightDiagonalX)
              {
                ulx = next_neighbour.intersection.lowerRightDiagonalX;
                lx = prev_neighbour.intersection.rightX;
                if (prev_neighbour.last_touching_side == 2)
                {
                  llx = prev_neighbour.intersection.upperRightDiagonalX;
                }
              }
              else if (prev_neighbour.intersection.lowerRightDiagonalX < next_neighbour.intersection.lowerRightDiagonalX)
              {
                uy = next_neighbour.intersection.bottomY;
                ulx = prev_neighbour.intersection.lowerRightDiagonalX;
              }
              else // prev_neighbour.intersection.lrx == next_neighbour.intersection.lrx
              {
                ulx = next_neighbour.intersection.lowerRightDiagonalX;
              }
            }
            case 3 ->
            {
              if (prev_neighbour.intersection.rightX > next_neighbour.intersection.rightX)
              {
                lx = next_neighbour.intersection.rightX;
                llx = prev_neighbour.intersection.upperRightDiagonalX;
                if (prev_neighbour.last_touching_side == 3)
                {
                  ly = prev_neighbour.intersection.topY;
                }
              }
              else if (prev_neighbour.intersection.rightX < next_neighbour.intersection.rightX)
              {
                lx = prev_neighbour.intersection.rightX;
                ulx = next_neighbour.intersection.lowerRightDiagonalX;
              }
              else // prev_neighbour.intersection.ry == next_neighbour.intersection.ry
              {
                lx = next_neighbour.intersection.rightX;
              }
            }
            case 4 ->
            {
              if (prev_neighbour.intersection.upperRightDiagonalX > next_neighbour.intersection.upperRightDiagonalX)
              {
                llx = next_neighbour.intersection.upperRightDiagonalX;
                ly = prev_neighbour.intersection.topY;
                if (prev_neighbour.last_touching_side == 4)
                {
                  lrx = prev_neighbour.intersection.upperLeftDiagonalX;
                }
              }
              else if (prev_neighbour.intersection.upperRightDiagonalX < next_neighbour.intersection.upperRightDiagonalX)
              {
                lx = next_neighbour.intersection.rightX;
                llx = prev_neighbour.intersection.upperRightDiagonalX;
              }
              else // prev_neighbour.intersection.urx == next_neighbour.intersection.urx
              {
                llx = next_neighbour.intersection.upperRightDiagonalX;
              }
            }
            case 5 ->
            {
              if (prev_neighbour.intersection.topY > next_neighbour.intersection.topY)
              {
                ly = next_neighbour.intersection.topY;
                lrx = prev_neighbour.intersection.upperLeftDiagonalX;
                if (prev_neighbour.last_touching_side == 5)
                {
                  rx = prev_neighbour.intersection.leftX;
                }
              }
              else if (prev_neighbour.intersection.topY < next_neighbour.intersection.topY)
              {
                ly = prev_neighbour.intersection.topY;
                llx = next_neighbour.intersection.upperRightDiagonalX;
              }
              else // prev_neighbour.intersection.uy == next_neighbour.intersection.uy
              {
                ly = next_neighbour.intersection.topY;
              }
            }
            case 6 ->
            {
              if (prev_neighbour.intersection.upperLeftDiagonalX < next_neighbour.intersection.upperLeftDiagonalX)
              {
                lrx = next_neighbour.intersection.upperLeftDiagonalX;
                rx = prev_neighbour.intersection.leftX;
                if (prev_neighbour.last_touching_side == 6)
                {
                  urx = prev_neighbour.intersection.lowerLeftDiagonalX;
                }
              }
              else if (prev_neighbour.intersection.upperLeftDiagonalX > next_neighbour.intersection.upperLeftDiagonalX)
              {
                ly = next_neighbour.intersection.topY;
                lrx = prev_neighbour.intersection.upperLeftDiagonalX;
              }
              else // prev_neighbour.intersection.ulx == next_neighbour.intersection.ulx
              {
                lrx = next_neighbour.intersection.upperLeftDiagonalX;
              }
            }
            case 7 ->
            {
              if (prev_neighbour.intersection.leftX < next_neighbour.intersection.leftX)
              {
                rx = next_neighbour.intersection.leftX;
                urx = prev_neighbour.intersection.lowerLeftDiagonalX;
                if (prev_neighbour.last_touching_side == 7)
                {
                  uy = prev_neighbour.intersection.bottomY;
                }
              }
              else if (prev_neighbour.intersection.leftX > next_neighbour.intersection.leftX)
              {
                rx = prev_neighbour.intersection.leftX;
                lrx = next_neighbour.intersection.upperLeftDiagonalX;
              }
              else // prev_neighbour.intersection.lx == next_neighbour.intersection.lx
              {
                rx = next_neighbour.intersection.leftX;
              }
            }
            default -> FRLogger.warn("Sorted45DegreeRoomNeighbour.calculate_new_incomplete: illegal touching side");
          }
          insert_incomplete_room(p_autoroute_engine, lx, ly, rx, uy, ulx, lrx, llx, urx);
        }
      }
      prev_neighbour = next_neighbour;
    }
  }

  /**
   * Helper class to sort the doors of an expansion room counterclockwise around the border of the
   * room shape.
   */
  private class SortedRoomNeighbour implements Comparable<SortedRoomNeighbour>
  {

    /**
     * The shape of the neighbour room
     */
    public final IntOctagon shape;
    /**
     * The intersection of this ExpansionRoom shape with the neighbour_shape
     */
    public final IntOctagon intersection;
    /**
     * The first side of the room shape, where the neighbour_shape touches
     */
    public final int first_touching_side;
    /**
     * The last side of the room shape, where the neighbour_shape touches
     */
    public final int last_touching_side;

    /**
     * Creates a new instance of SortedRoomNeighbour and calculates the first and last touching
     * sides with the room shape. this.last_touching_side will be -1, if sorting did not work
     * because the room_shape is contained in the neighbour shape.
     */
    public SortedRoomNeighbour(IntOctagon p_neighbour_shape, IntOctagon p_intersection)
    {
      shape = p_neighbour_shape;
      intersection = p_intersection;

      if (intersection.bottomY == room_shape.bottomY && intersection.lowerLeftDiagonalX > room_shape.lowerLeftDiagonalX)
      {
        this.first_touching_side = 0;
      }
      else if (intersection.lowerRightDiagonalX == room_shape.lowerRightDiagonalX && intersection.bottomY > room_shape.bottomY)
      {
        this.first_touching_side = 1;
      }
      else if (intersection.rightX == room_shape.rightX && intersection.lowerRightDiagonalX < room_shape.lowerRightDiagonalX)
      {
        this.first_touching_side = 2;
      }
      else if (intersection.upperRightDiagonalX == room_shape.upperRightDiagonalX && intersection.rightX < room_shape.rightX)
      {
        this.first_touching_side = 3;
      }
      else if (intersection.topY == room_shape.topY && intersection.upperRightDiagonalX < room_shape.upperRightDiagonalX)
      {
        this.first_touching_side = 4;
      }
      else if (intersection.upperLeftDiagonalX == room_shape.upperLeftDiagonalX && intersection.topY < room_shape.topY)
      {
        this.first_touching_side = 5;
      }
      else if (intersection.leftX == room_shape.leftX && intersection.upperLeftDiagonalX > room_shape.upperLeftDiagonalX)
      {
        this.first_touching_side = 6;
      }
      else if (intersection.lowerLeftDiagonalX == room_shape.lowerLeftDiagonalX && intersection.leftX > room_shape.leftX)
      {
        this.first_touching_side = 7;
      }
      else
      {
        // the room_shape may be contained in the neighbour_shape
        this.first_touching_side = -1;
        this.last_touching_side = -1;
        return;
      }

      if (intersection.lowerLeftDiagonalX == room_shape.lowerLeftDiagonalX && intersection.bottomY > room_shape.bottomY)
      {
        this.last_touching_side = 7;
      }
      else if (intersection.leftX == room_shape.leftX && intersection.lowerLeftDiagonalX > room_shape.lowerLeftDiagonalX)
      {
        this.last_touching_side = 6;
      }
      else if (intersection.upperLeftDiagonalX == room_shape.upperLeftDiagonalX && intersection.leftX > room_shape.leftX)
      {
        this.last_touching_side = 5;
      }
      else if (intersection.topY == room_shape.topY && intersection.upperLeftDiagonalX > room_shape.upperLeftDiagonalX)
      {
        this.last_touching_side = 4;
      }
      else if (intersection.upperRightDiagonalX == room_shape.upperRightDiagonalX && intersection.topY < room_shape.topY)
      {
        this.last_touching_side = 3;
      }
      else if (intersection.rightX == room_shape.rightX && intersection.upperRightDiagonalX < room_shape.upperRightDiagonalX)
      {
        this.last_touching_side = 2;
      }
      else if (intersection.lowerRightDiagonalX == room_shape.lowerRightDiagonalX && intersection.rightX < room_shape.rightX)
      {
        this.last_touching_side = 1;
      }
      else if (intersection.bottomY == room_shape.bottomY && intersection.lowerRightDiagonalX < room_shape.lowerRightDiagonalX)
      {
        this.last_touching_side = 0;
      }
      else
      {
        // the room_shape may be contained in the neighbour_shape
        this.last_touching_side = -1;
        return;
      }

      int next_side_no = this.first_touching_side;
      for (; ; )
      {
        int curr_side_no = next_side_no;
        next_side_no = (next_side_no + 1) % 8;
        if (!edge_interior_touches_obstacle[curr_side_no])
        {
          boolean touch_only_at_corner = false;
          if (curr_side_no == this.first_touching_side)
          {
            if (intersection
                .corner(curr_side_no)
                .equals(room_shape.corner(next_side_no)))
            {
              touch_only_at_corner = true;
            }
          }
          if (curr_side_no == this.last_touching_side)
          {
            if (intersection
                .corner(next_side_no)
                .equals(room_shape.corner(curr_side_no)))
            {
              touch_only_at_corner = true;
            }
          }
          if (!touch_only_at_corner)
          {
            edge_interior_touches_obstacle[curr_side_no] = true;
          }
        }
        if (curr_side_no == this.last_touching_side)
        {
          break;
        }
      }
    }

    /**
     * Compare function for or sorting the neighbours in counterclock sense around the border of the
     * room shape in ascending order.
     */
    @Override
    public int compareTo(SortedRoomNeighbour p_other)
    {
      if (this.first_touching_side > p_other.first_touching_side)
      {
        return 1;
      }
      if (this.first_touching_side < p_other.first_touching_side)
      {
        return -1;
      }

      // now the first touch of this and p_other is at the same side
      IntOctagon is1 = this.intersection;
      IntOctagon is2 = p_other.intersection;
      int cmp_value;

      switch (first_touching_side)
      {
        case 0 -> cmp_value = is1.corner(0).x - is2.corner(0).x;
        case 1 -> cmp_value = is1.corner(1).x - is2.corner(1).x;
        case 2 -> cmp_value = is1.corner(2).y - is2.corner(2).y;
        case 3 -> cmp_value = is1.corner(3).y - is2.corner(3).y;
        case 4 -> cmp_value = is2.corner(4).x - is1.corner(4).x;
        case 5 -> cmp_value = is2.corner(5).x - is1.corner(5).x;
        case 6 -> cmp_value = is2.corner(6).y - is1.corner(6).y;
        case 7 -> cmp_value = is2.corner(7).y - is1.corner(7).y;
        default ->
        {
          FRLogger.warn("SortedRoomNeighbour.compareTo: first_touching_side out of range ");
          return 0;
        }
      }

      if (cmp_value == 0)
      {
        // The first touching points of this neighbour and p_other with the room shape are equal.
        // Compare the last touching points.
        int this_touching_side_diff = (this.last_touching_side - this.first_touching_side + 8) % 8;
        int other_touching_side_diff = (p_other.last_touching_side - p_other.first_touching_side + 8) % 8;
        if (this_touching_side_diff > other_touching_side_diff)
        {
          return 1;
        }
        if (this_touching_side_diff < other_touching_side_diff)
        {
          return -1;
        }
        // now the last touch of this and p_other is at the same side
        switch (last_touching_side)
        {
          case 0 -> cmp_value = is1.corner(1).x - is2.corner(1).x;
          case 1 -> cmp_value = is1.corner(2).x - is2.corner(2).x;
          case 2 -> cmp_value = is1.corner(3).y - is2.corner(3).y;
          case 3 -> cmp_value = is1.corner(4).y - is2.corner(4).y;
          case 4 -> cmp_value = is2.corner(5).x - is1.corner(5).x;
          case 5 -> cmp_value = is2.corner(6).x - is1.corner(6).x;
          case 6 -> cmp_value = is2.corner(7).y - is1.corner(7).y;
          case 7 -> cmp_value = is2.corner(0).y - is1.corner(0).y;
        }
      }
      return cmp_value;
    }
  }
}