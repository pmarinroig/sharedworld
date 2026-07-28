-- Redeeming an invite used to overwrite the redeemer's membership role with
-- 'member', so an owner who redeemed a code to their own world was silently
-- demoted. Restore the owner role wherever the membership belongs to the
-- world's recorded owner.
UPDATE world_memberships
SET role = 'owner'
WHERE role != 'owner'
  AND EXISTS (
    SELECT 1 FROM worlds w
    WHERE w.id = world_memberships.world_id
      AND w.owner_uuid = world_memberships.player_uuid
  );
