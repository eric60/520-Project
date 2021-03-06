-- DROP TABLE IF EXISTS nodes CASCADE;
-- DROP TABLE IF EXISTS edges;
-- DROP EXTENSION IF EXISTS postgis;

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE TABLE IF NOT EXISTS nodes(
    id bigint PRIMARY KEY,
--     point geometry(Point, 4326),
    point GEOGRAPHY(Point),
    elevation double precision
);

CREATE TABLE IF NOT EXISTS edges(
    id SERIAL PRIMARY KEY,
    src bigint references nodes(id),
    dest bigint references nodes(id),
    distance double precision
);

CREATE INDEX IF NOT EXISTS nodes_point_gix
ON nodes USING GIST (point);

CREATE OR REPLACE VIEW nodesAndEdges AS
select n.id, n.point as point, n.elevation, e.src, e.dest
from nodes n left outer join edges e on n.id=e.src
order by n.id;

-- INSERT INTO nodes(geog) values('POINT(-118.4079 33.9434)');
-- INSERT INTO nodes(geog) values('POINT(2.5559 49.0083)');

