import { ReactComponent as ThumbsUp } from '@/assets/icon/thumbs-up.svg';

export default function DeleteIcon({
  isUp,
  width,
  height,
  color,
  margin,
  onClick,
}) {
  return (
    <ThumbsUp
      transform={isUp ? '' : 'scale(1, -1)'}
      onClick={onClick}
      className="hover-pointer"
      style={{
        width: width + 'px',
        height: height + 'px',
        fill: color,
        margin: margin,
      }}
    />
  );
}
